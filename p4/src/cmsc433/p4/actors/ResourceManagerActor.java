package cmsc433.p4.actors;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Iterator;

import cmsc433.p4.enums.*;
import cmsc433.p4.messages.*;
import cmsc433.p4.util.*;
import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.Props;
import akka.actor.UntypedActor;

public class ResourceManagerActor extends UntypedActor {
	
	//Contains info on user and access level associated with given resource.
	private class UserAccess {
			
		private ActorRef user;
		private AccessType access;
			
		private UserAccess(ActorRef user, AccessType access) {
			this.user = user;
			this.access = access;
		}
			
		private ActorRef getUser() {
			return user;
		}
			
		private AccessType getAccess() {
			return access;
		}
		
	}
	
	private class DiscoverClass {
		private Object requestMsg;
		private int count = 0;
		
		private DiscoverClass(Object request) {
			this.requestMsg = request;
		}
		
		private void incrementCount() {
			count+=1;
		}
		
		private void decrementCount() {
			count-=1;
		}
		
		private int getCount() {
			return count;
		}
		
		private Object getRequestMsg() {
			return requestMsg;
		}
		
	}
	
	private ActorRef logger;					// Actor to send logging messages to
	private Map<String, ActorRef> knownRemote = new HashMap<String, ActorRef>(); //Remote resource + manager.
	private Map<String, Resource> localResource = new HashMap<String, Resource>(); //Resources local to manager.
	private HashSet<ActorRef> allManagers = new HashSet<ActorRef>(); //All managers in ActorSystem
	private HashSet<ActorRef> localUsers = new HashSet<ActorRef>(); //Users this manager will deal with.
	
	//Queue of requests that are being blocked.
	private List<AccessRequestMsg> accessQueue = new LinkedList<AccessRequestMsg>();	
	
	//Map of resource and list of users and access they have on that resource, how we implement locking.
	private Map<String, List<UserAccess>> resourceAccess = new HashMap<String, List<UserAccess>>(); 
	
	//Map of resources marked for disable and actor that did it.
	private Map<String, List<ManagementRequestMsg>> pendingDisable = new HashMap<String, List<ManagementRequestMsg>>();
	
	//Map of unknown resources we are trying to find.
	private Map<String, List<DiscoverClass>> discoveryMap = new HashMap<String, List<DiscoverClass>>();
	
	/**
	 * Props structure-generator for this class.
	 * @return  Props structure
	 */
	static Props props (ActorRef logger) {
		return Props.create(ResourceManagerActor.class, logger);
	}
	
	/**
	 * Factory method for creating resource managers
	 * @param logger			Actor to send logging messages to
	 * @param system			Actor system in which manager will execute
	 * @return					Reference to new manager
	 */
	public static ActorRef makeResourceManager (ActorRef logger, ActorSystem system) {
		ActorRef newManager = system.actorOf(props(logger));
		return newManager;
	}
	
	/**
	 * Sends a message to the Logger Actor
	 * @param msg The message to be sent to the logger
	 */
	public void log (LogMsg msg) {
		logger.tell(msg, getSelf());
	}
	
	/**
	 * Constructor
	 * 
	 * @param logger			Actor to send logging messages to
	 */
	private ResourceManagerActor(ActorRef logger) {
		super();
		this.logger = logger;
	}
	
	//Carries out configuration requests to this actor.
	private void configurationHelper(Object o, ActorRef sender) {
		if (o instanceof AddRemoteManagersRequestMsg) {
			AddRemoteManagersRequestMsg msg = (AddRemoteManagersRequestMsg)o;

			ArrayList<ActorRef> list = new ArrayList<ActorRef>(msg.getManagerList());
			
			for (ActorRef actor : list) {
				if (!actor.equals(getSelf())) {
					allManagers.add(actor);
				}
			}
			
			sender.tell(new AddRemoteManagersResponseMsg(msg), getSelf());
			
		} else if (o instanceof AddInitialLocalResourcesRequestMsg) {
			AddInitialLocalResourcesRequestMsg msg = (AddInitialLocalResourcesRequestMsg)o;
			
			ArrayList<Resource> list = new ArrayList<Resource>(msg.getLocalResources());
			
			for (Resource resource : list) {
				resource.enable();
				String name = resource.getName();
				localResource.put(name, resource);
				logger.tell(LogMsg.makeLocalResourceCreatedLogMsg(getSelf(), name), getSelf());
				logger.tell(LogMsg.makeResourceStatusChangedLogMsg(getSelf(), name, resource.getStatus()), getSelf());
			}
			
			AddInitialLocalResourcesResponseMsg response = new AddInitialLocalResourcesResponseMsg(msg);
			sender.tell(response, getSelf());
			
		} else if (o instanceof AddLocalUsersRequestMsg) {
			AddLocalUsersRequestMsg msg = (AddLocalUsersRequestMsg)o;
			
			ArrayList<ActorRef> list = new ArrayList<ActorRef>(msg.getLocalUsers());
			
			localUsers.addAll(list);
			
			AddLocalUsersResponseMsg response = new AddLocalUsersResponseMsg(msg);
			sender.tell(response, getSelf());
		}
	}
	
	//Handle all access requests.
	private void accessRequestHelper(AccessRequestMsg msg, boolean isQueued) {
		AccessRequest access = msg.getAccessRequest();
		ActorRef sender = msg.getReplyTo();
		
		if (!isQueued) {
			logger.tell(LogMsg.makeAccessRequestReceivedLogMsg(msg.getReplyTo(), getSelf(), access), getSelf());
		}
			
		if (!localResource.containsKey(access.getResourceName())) {
			
			if (knownRemote.containsKey(access.getResourceName())) {
				ActorRef theRemote = knownRemote.get(access.getResourceName());
				theRemote.tell(msg, getSelf());
				logger.tell(LogMsg.makeAccessRequestForwardedLogMsg(getSelf(), theRemote, access), getSelf());
			} else {
				
				//Check for the desired target.
				DiscoverClass aDiscover = new DiscoverClass(msg);
				
				if (discoveryMap.containsKey(access.getResourceName())) {
					discoveryMap.get(access.getResourceName()).add(aDiscover);
				} else {
					for (ActorRef managers : allManagers) {
						aDiscover.incrementCount();
						WhoHasResourceRequestMsg message = new WhoHasResourceRequestMsg(access.getResourceName(), getSelf());
						managers.tell(message, getSelf());
					}
					List<DiscoverClass> lst = new LinkedList<DiscoverClass>();
					lst.add(aDiscover);
					discoveryMap.put(access.getResourceName(), lst);
				}
				
			}
			
		} else {
			AccessRequestType typeRequest = access.getType();
			String resourceName = access.getResourceName();
			
			//Make sure the resource wasn't disabled for some reason.
			ResourceStatus status = localResource.get(resourceName).getStatus();
			if (status == ResourceStatus.DISABLED || pendingDisable.containsKey(resourceName)) {
				AccessRequestDenialReason whyTho = AccessRequestDenialReason.RESOURCE_DISABLED;
				AccessRequestDeniedMsg denied = new AccessRequestDeniedMsg(access, whyTho);
				sender.tell(denied, getSelf());
				logger.tell(LogMsg.makeAccessRequestDeniedLogMsg(sender, getSelf(), access, whyTho), getSelf());
				return;
			}
			
			if (!resourceAccess.containsKey(resourceName)) {
				resourceAccess.put(resourceName, new ArrayList<UserAccess>());
			}
			
			if (resourceAccess.get(resourceName).isEmpty()) {
				
				if (typeRequest == AccessRequestType.CONCURRENT_READ_BLOCKING || typeRequest == AccessRequestType.CONCURRENT_READ_NONBLOCKING) {
					UserAccess object = new UserAccess(sender, AccessType.CONCURRENT_READ);
					resourceAccess.get(resourceName).add(object);
					sender.tell(new AccessRequestGrantedMsg(access), getSelf());
					logger.tell(LogMsg.makeAccessRequestGrantedLogMsg(sender, getSelf(), access), getSelf());
				} else if (typeRequest == AccessRequestType.EXCLUSIVE_WRITE_BLOCKING || typeRequest == AccessRequestType.EXCLUSIVE_WRITE_NONBLOCKING) {
					UserAccess object = new UserAccess(sender, AccessType.EXCLUSIVE_WRITE);
					resourceAccess.get(resourceName).add(object);
					sender.tell(new AccessRequestGrantedMsg(access), getSelf());
					logger.tell(LogMsg.makeAccessRequestGrantedLogMsg(sender, getSelf(), access), getSelf());
				}
				
			} else {
				List<UserAccess> list = resourceAccess.get(resourceName);
				boolean canAccess = true;
				
				for (UserAccess user : list) {
					AccessType type = user.getAccess();
					ActorRef curr = user.getUser();
					
					if (type == AccessType.EXCLUSIVE_WRITE && !curr.equals(sender)) {
						canAccess = false;
						break;
					} else if (type == AccessType.CONCURRENT_READ && !curr.equals(sender)) {
						AccessRequestType badOne = AccessRequestType.EXCLUSIVE_WRITE_BLOCKING;
						AccessRequestType badTwo = AccessRequestType.EXCLUSIVE_WRITE_NONBLOCKING;
						
						if (typeRequest == badOne || typeRequest == badTwo) {
							canAccess = false;
							break;
						}
					}
				}
				
				//See if there are conditions preventing grant request.
				if (canAccess) {
					
					UserAccess newAccess = null;
					if (typeRequest == AccessRequestType.CONCURRENT_READ_BLOCKING || typeRequest == AccessRequestType.CONCURRENT_READ_NONBLOCKING) {
						newAccess = new UserAccess(sender, AccessType.CONCURRENT_READ);
					} else {
						newAccess = new UserAccess(sender, AccessType.EXCLUSIVE_WRITE);
					}
					
					resourceAccess.get(resourceName).add(newAccess);
					AccessRequestGrantedMsg granted = new AccessRequestGrantedMsg(access);
					logger.tell(LogMsg.makeAccessRequestGrantedLogMsg(sender, getSelf(), access), getSelf());
					sender.tell(granted, getSelf());
					
				} else {
					
					if (typeRequest == AccessRequestType.CONCURRENT_READ_BLOCKING || typeRequest == AccessRequestType.EXCLUSIVE_WRITE_BLOCKING) {
						accessQueue.add(msg);
					} else {
						AccessRequestDenialReason whyTho = AccessRequestDenialReason.RESOURCE_BUSY;
						AccessRequestDeniedMsg rejected = new AccessRequestDeniedMsg(access, whyTho);
						sender.tell(rejected, getSelf());
						logger.tell(LogMsg.makeAccessRequestDeniedLogMsg(sender, getSelf(), access, whyTho), getSelf());
					}
					
				}
				
			}
			
		}
	}
	
	//Helpers to release a user's access on some resource.
	private void accessReleaseHelper(AccessReleaseMsg msg) {
		AccessRelease release = msg.getAccessRelease();
		ActorRef sender = msg.getSender();
		
		String resource = release.getResourceName();
		AccessType type = release.getType();
		
		//System.out.println(resource);
		
		logger.tell(LogMsg.makeAccessReleaseReceivedLogMsg(sender, getSelf(), release), getSelf());
		
		if (!localResource.containsKey(resource)) {
			
			if (knownRemote.containsKey(resource)) {
				ActorRef remote = knownRemote.get(resource);
				remote.tell(msg, sender);
				
				logger.tell(LogMsg.makeAccessReleaseForwardedLogMsg(getSelf(), remote, release), getSelf());
				
			} else {
				//Check for the desired target.
				DiscoverClass aDiscover = new DiscoverClass(msg);
				
				if (discoveryMap.containsKey(release.getResourceName())) {
					discoveryMap.get(release.getResourceName()).add(aDiscover);
				} else {
					for (ActorRef managers : allManagers) {
						aDiscover.incrementCount();
						WhoHasResourceRequestMsg message = new WhoHasResourceRequestMsg(release.getResourceName(), getSelf());
						managers.tell(message, getSelf());
					}
					List<DiscoverClass> lst = new LinkedList<DiscoverClass>();
					lst.add(aDiscover);
					discoveryMap.put(release.getResourceName(), lst);
				}
			}
			
		} else {
			List<UserAccess> list = resourceAccess.get(resource);
			Iterator<UserAccess> iter = list.iterator();
			boolean hasAccess = false;
			
			while (iter.hasNext()) {
				UserAccess userAccess = iter.next();
				ActorRef holder = userAccess.getUser();
				AccessType access = userAccess.getAccess();
				if (holder.equals(sender) && access.equals(type)) {
					iter.remove();
					hasAccess = true;
					logger.tell(LogMsg.makeAccessReleasedLogMsg(sender, getSelf(), release), getSelf());
					break;
				}
			}
			
			//System.out.println(hasAccess);
			if (!hasAccess) {
				logger.tell(LogMsg.makeAccessReleaseIgnoredLogMsg(sender, getSelf(), release), getSelf());
			}
			
			if (list.isEmpty()) {
				if (pendingDisable.containsKey(resource) && localResource.get(resource).getStatus() == ResourceStatus.ENABLED) {
					localResource.get(resource).disable();
					for (ManagementRequestMsg respondTo : pendingDisable.get(resource)) {
						ManagementRequest management = respondTo.getRequest();
						ActorRef replyTo = respondTo.getReplyTo();
						ManagementRequestGrantedMsg grant = new ManagementRequestGrantedMsg(management);
						replyTo.tell(grant, getSelf());
						logger.tell(LogMsg.makeResourceStatusChangedLogMsg(getSelf(), resource, localResource.get(resource).getStatus()), getSelf());
						logger.tell(LogMsg.makeManagementRequestGrantedLogMsg(replyTo, getSelf(), management), getSelf());
					}
				}
			}
		}
	}
	
	//Carry out management requests.
	private void manageRequestHelper(ManagementRequestMsg msg) {
		ManagementRequest management = msg.getRequest();
		ActorRef replyTo = msg.getReplyTo();
		
		String resource = management.getResourceName();
		ManagementRequestType type = management.getType();
		
		logger.tell(LogMsg.makeManagementRequestReceivedLogMsg(replyTo, getSelf(), management), getSelf());
		
		if (!localResource.containsKey(resource)) {
			
			if (knownRemote.containsKey(resource)) {
				ActorRef remote = knownRemote.get(resource);
				remote.tell(msg, replyTo);
				
				logger.tell(LogMsg.makeManagementRequestForwardedLogMsg(getSelf(), remote, management), getSelf());
				
			} else {
				//Check for the desired target.
				DiscoverClass aDiscover = new DiscoverClass(msg);
				
				if (discoveryMap.containsKey(resource)) {
					discoveryMap.get(resource).add(aDiscover);
				} else {
					for (ActorRef managers : allManagers) {
						aDiscover.incrementCount();
						WhoHasResourceRequestMsg message = new WhoHasResourceRequestMsg(resource, getSelf());
						managers.tell(message, getSelf());
					}
					List<DiscoverClass> lst = new LinkedList<DiscoverClass>();
					lst.add(aDiscover);
					discoveryMap.put(resource, lst);
				}
			}
			
		} else {
			
			if (type == ManagementRequestType.DISABLE) {
				if (localResource.get(resource).getStatus() == ResourceStatus.ENABLED) {
					
					if (!resourceAccess.containsKey(resource)) {
						resourceAccess.put(resource, new ArrayList<UserAccess>());
					}
					
					//System.out.println(resourceAccess.containsKey(resource));
					//System.out.println(resource);
					List<UserAccess> list = resourceAccess.get(resource);
					boolean canDisable = true;
					
					for (UserAccess user : list) {
						if (user.getUser().equals(replyTo)) {
							canDisable = false;
							break;
						}
					}
					
					//User currently has an access.
					if (!canDisable) {
						ManagementRequestDenialReason reason = ManagementRequestDenialReason.ACCESS_HELD_BY_USER;
						ManagementRequestDeniedMsg deny = new ManagementRequestDeniedMsg(management, reason);
						
						logger.tell(LogMsg.makeManagementRequestDeniedLogMsg(replyTo, getSelf(), management, reason), getSelf());
						
						replyTo.tell(deny, getSelf());
					} else {
						Iterator<AccessRequestMsg> iter = accessQueue.iterator();
						
						while (iter.hasNext()) {
							AccessRequestMsg access = iter.next();
							AccessRequest ele = access.getAccessRequest();
							if (ele.getResourceName().equals(resource)) {
								iter.remove();
								AccessRequestDenialReason reason = AccessRequestDenialReason.RESOURCE_DISABLED;
								AccessRequestDeniedMsg deny = new AccessRequestDeniedMsg(ele, reason);
								access.getReplyTo().tell(deny, getSelf());
								logger.tell(LogMsg.makeAccessRequestDeniedLogMsg(access.getReplyTo(), getSelf(), ele, reason), getSelf());
							}
						}
						
						if (list.isEmpty()) {
							localResource.get(resource).disable();
							
							if (!pendingDisable.containsKey(resource)) {
								List<ManagementRequestMsg> lst = new LinkedList<ManagementRequestMsg>();
								pendingDisable.put(resource, lst);
							}
							
							pendingDisable.get(resource).add(msg);
							ManagementRequestGrantedMsg grant = new ManagementRequestGrantedMsg(management);
							replyTo.tell(grant, getSelf());
							logger.tell(LogMsg.makeResourceStatusChangedLogMsg(getSelf(), resource, localResource.get(resource).getStatus()), getSelf());
							logger.tell(LogMsg.makeManagementRequestGrantedLogMsg(replyTo, getSelf(), management), getSelf());
						} else {
							if (!pendingDisable.containsKey(resource)) {
								List<ManagementRequestMsg> lst = new LinkedList<ManagementRequestMsg>();
								pendingDisable.put(resource, lst);
							}
							
							pendingDisable.get(resource).add(msg);
						}
					}
				} else {
					ManagementRequestGrantedMsg grant = new ManagementRequestGrantedMsg(management);
					replyTo.tell(grant, getSelf());
					logger.tell(LogMsg.makeManagementRequestGrantedLogMsg(replyTo, getSelf(), management), getSelf());
				}
			} else if (type == ManagementRequestType.ENABLE) {
				Resource device = localResource.get(resource);
				if (device.getStatus() == ResourceStatus.DISABLED) {
					device.enable();
					pendingDisable.remove(resource);
				}
				
				logger.tell(LogMsg.makeResourceStatusChangedLogMsg(getSelf(), resource, device.getStatus()), getSelf());
				logger.tell(LogMsg.makeManagementRequestGrantedLogMsg(replyTo, getSelf(), management), getSelf());
				ManagementRequestGrantedMsg grant = new ManagementRequestGrantedMsg(management);
				replyTo.tell(grant, getSelf());
			}
			
		}
		
	}
	
	//After we released access for resources we want to check to see if any blocking access requests
	//can now be granted.
	private void grantRequestOnRelease() {
		if (!accessQueue.isEmpty()) {
			Iterator<AccessRequestMsg> iter = accessQueue.iterator();
			while (iter.hasNext()) {
				AccessRequestMsg pending = iter.next();
				AccessRequest request = pending.getAccessRequest();
				String resourceName = request.getResourceName();
				ActorRef sender = pending.getReplyTo();
				AccessRequestType typeRequest = request.getType();
				
				List<UserAccess> list = resourceAccess.get(resourceName);
				boolean canAccess = true;
				
				for (UserAccess user : list) {
					AccessType type = user.getAccess();
					ActorRef curr = user.getUser();
					
					if (type == AccessType.EXCLUSIVE_WRITE && !curr.equals(sender)) {
						canAccess = false;
					} else if (type == AccessType.CONCURRENT_READ && !curr.equals(sender)) {
						AccessRequestType badOne = AccessRequestType.EXCLUSIVE_WRITE_BLOCKING;
						AccessRequestType badTwo = AccessRequestType.EXCLUSIVE_WRITE_NONBLOCKING;
						
						if (typeRequest == badOne || typeRequest == badTwo) {
							canAccess = false;
						}
					}
				}
				
				if (canAccess) {
					accessRequestHelper(pending, true);
					iter.remove();
				} else {
					break;
				}
			}
		}
	}
	
	// You may want to add data structures for managing local resources and users, storing
	// remote managers, etc. Also you cannot use Patterns.ask() to communicate with other
	// Actors, only use tell().
 	
	/* (non-Javadoc)
	 * 
	 * You must provide an implementation of the onReceive method below.
	 * 
	 * @see akka.actor.UntypedActor#onReceive(java.lang.Object)
	 */
	@Override
	public void onReceive(Object o) throws Exception {
		if (o instanceof AddRemoteManagersRequestMsg) {
			ActorRef sender = getSender();
			configurationHelper(o, sender);
			
		} else if (o instanceof AddInitialLocalResourcesRequestMsg) {
			ActorRef sender = getSender();
			configurationHelper(o, sender);
			
		} else if (o instanceof AddLocalUsersRequestMsg) {
			ActorRef sender = getSender();
			configurationHelper(o, sender);	
			
		} else if (o instanceof AccessReleaseMsg) {		//Release user access on a resource.
			AccessReleaseMsg msg = (AccessReleaseMsg)o;
			accessReleaseHelper(msg);
			grantRequestOnRelease();
			
		//Grant or deny a user access to a resource.	
		} else if (o instanceof AccessRequestMsg) {		
			AccessRequestMsg msg = (AccessRequestMsg)o;
			accessRequestHelper(msg, false);
			
		} else if (o instanceof ManagementRequestMsg) {
			ManagementRequestMsg msg = (ManagementRequestMsg)o;
			manageRequestHelper(msg);
			
		} else if (o instanceof WhoHasResourceRequestMsg) {
			WhoHasResourceRequestMsg msg = (WhoHasResourceRequestMsg)o;
			String resource = msg.getResourceName();
			ActorRef resourceManager = msg.getSender();
			
			boolean result = localResource.containsKey(resource);
			
			WhoHasResourceResponseMsg response = new WhoHasResourceResponseMsg(resource, result, getSelf());
			resourceManager.tell(response, getSelf());
			
		} else if (o instanceof WhoHasResourceResponseMsg) {
			WhoHasResourceResponseMsg msg = (WhoHasResourceResponseMsg)o;
			String resource = msg.getResourceName();
			boolean trueFalse = msg.getResult();
			ActorRef toSend = msg.getSender();
			
			if (trueFalse) {
				logger.tell(LogMsg.makeRemoteResourceDiscoveredLogMsg(getSelf(), toSend, resource), getSelf());
				List<DiscoverClass> process = discoveryMap.get(resource);
				if (process != null) {
					for (DiscoverClass ele : process) {
						if (ele.getRequestMsg() instanceof AccessRequestMsg) {
							AccessRequestMsg access = (AccessRequestMsg)ele.getRequestMsg();
							AccessRequest req = access.getAccessRequest();
							toSend.tell(access, getSelf());
							logger.tell(LogMsg.makeAccessRequestForwardedLogMsg(getSelf(), toSend, req), getSelf());
						} else if (ele.getRequestMsg() instanceof ManagementRequestMsg) {
							ManagementRequestMsg manage = (ManagementRequestMsg)ele.getRequestMsg();
							ManagementRequest req = manage.getRequest();
							toSend.tell(manage, getSelf());
							logger.tell(LogMsg.makeManagementRequestForwardedLogMsg(getSelf(), toSend, req), getSelf());
						} else if (ele.getRequestMsg() instanceof AccessReleaseMsg) {
							AccessReleaseMsg rel = (AccessReleaseMsg)ele.getRequestMsg();
							AccessRelease release = rel.getAccessRelease();
							toSend.tell(rel, getSelf());
							logger.tell(LogMsg.makeAccessReleaseForwardedLogMsg(getSelf(), toSend, release), getSelf());
						}
					}
					discoveryMap.remove(resource);
					knownRemote.put(resource, toSend);
				}
			} else {
				if (discoveryMap.get(resource) != null) {
					DiscoverClass something = discoveryMap.get(resource).get(0);
					if (something != null) {
						something.decrementCount();
						if (something.getCount() == 0) {
							for (DiscoverClass ele : discoveryMap.get(resource)) {
								if (ele.getRequestMsg() instanceof AccessRequestMsg) {
									AccessRequestMsg access = (AccessRequestMsg)ele.getRequestMsg();
									AccessRequest req = access.getAccessRequest();
									AccessRequestDenialReason res = AccessRequestDenialReason.RESOURCE_NOT_FOUND;
									AccessRequestDeniedMsg deny = new AccessRequestDeniedMsg(req, res);
									access.getReplyTo().tell(deny, getSelf());
									logger.tell(LogMsg.makeAccessRequestDeniedLogMsg(access.getReplyTo(), getSelf(), req, res), getSelf());
								} else if (ele.getRequestMsg() instanceof ManagementRequestMsg) {
									ManagementRequestMsg manage = (ManagementRequestMsg)ele.getRequestMsg();
									ManagementRequest req = manage.getRequest();
									ManagementRequestDenialReason res = ManagementRequestDenialReason.RESOURCE_NOT_FOUND;
									ManagementRequestDeniedMsg deny = new ManagementRequestDeniedMsg(req, res);
									manage.getReplyTo().tell(deny, getSelf());
									logger.tell(LogMsg.makeManagementRequestDeniedLogMsg(manage.getReplyTo(), getSelf(), req, res), getSelf());
								} else if (ele.getRequestMsg() instanceof AccessReleaseMsg) {
									AccessReleaseMsg release = (AccessReleaseMsg)ele.getRequestMsg();
									AccessRelease theObj = release.getAccessRelease();
									ActorRef user = release.getSender();
									logger.tell(LogMsg.makeAccessReleaseIgnoredLogMsg(user, getSelf(), theObj), getSelf());							
								}
							}
							discoveryMap.remove(resource);
						}
					}
				}
			}	
		}
	}
}
