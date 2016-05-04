/*
 * @note Jacob Added
 * This is the "core" of the Opportunistic Caching Algorithm. We hook into when messages are routed to cache them. This allows for a speedup.
 * For simplicity, cache sizes are identical to buffer sizes. Buffer is not touched so we do not screw with normal routing
 */
package routing;

import core.Settings;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import core.Message;
import core.Connection;
import core.DTNHost;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Epidemic message router with drop-oldest buffer and only single transferring
 * connections at a time.
 */
public class OpportunisticDataCachingRouter extends ActiveRouter {

	private int cacheSize;
	private HashMap<String, Message> cachedMessages;
	private HashMap<String, Integer> timesSeen; // used to calculate popularity

	public static final int COUNT_CRITICALITY_MIN = Message.CRITICALITY_MIN;
	public static final int COUNT_CRITICALITY_MAX = Message.CRITICALITY_MAX; // by default count all
	public static final int COUNT_AFTER = 21500; // half of default time, TODO: read from settings and *0.5 here....

	/**
	 * Constructor. Creates a new message router based on the settings in
	 * the given Settings object.
	 * @param s The settings object
	 */
	public OpportunisticDataCachingRouter(Settings s) {
		super(s);

		this.cacheSize = this.getBufferSize();
		this.cachedMessages = new HashMap<String, Message>();
		this.timesSeen = new HashMap<String, Integer>();
	}

	/**
	 * Copy constructor.
	 * @param r The router prototype where setting values are copied from
	 */
	protected OpportunisticDataCachingRouter(OpportunisticDataCachingRouter r) {
		super(r);

		this.cacheSize = this.getBufferSize();
		this.cachedMessages = new HashMap<String, Message>();
		this.timesSeen = new HashMap<String, Integer>();
	}


	// BEGIN: The main parts I've added

		/**
	 * Returns a cached message by ID.
	 * @param id ID of the message
	 * @return The message
	 */
	protected Message getCachedMessage(String id) {
		return this.cachedMessages.get(id);
	}

	/**
	 * Checks if this router has a cached message with certain id buffered.
	 * @param id Identifier of the message
	 * @return True if the router has cached message with this id, false if not
	 */
	public boolean hasCachedMessage(String id) {
		return this.cachedMessages.containsKey(id);
	}

	/**
	 * Caches a message
	 * @param message the message to cache
	 */
	public void cacheMessage(Message m) {
		//System.out.println("cahcing " + m.getId());
		this.cachedMessages.put(m.getId(), m);
	}

		/**
	 * Checks how much space is left in the cache
	 * @return size of cache left
	 */
	public int getRemainingCacheSize() {
		int cachedSize = 0;

		for(String key : this.cachedMessages.keySet()) {
			Message cached = this.cachedMessages.get(key);
			cachedSize += cached.getSize();
		}

		return this.cacheSize - cachedSize;
	}



	// Override to try to cache it
	@Override
	public int receiveMessage(Message m, DTNHost from) {
		int returns = super.receiveMessage(m, from);

		//System.out.println("connection from " + from.toString());

		if(m.getProperty("cached") != null) {
			return returns; // no need to cache a cached response
		}

		Message request = m.getRequest();
		if(request != null) {
			if(this.hasCachedMessage(request.getId())) { // then it's a cache hit!
				this.storeCount("cache_hit", 1);
				Message newMessage = new Message(this.getHost(), request.getFrom(), request.getId() + "_CACHED", request.getSize());
				newMessage.addProperty("cached", true);
				this.getHost().createNewMessage(newMessage);
			}
			else {
				this.storeCount("cache_miss", 1);
			}

			if(request.getCreationTime() > OpportunisticDataCachingRouter.COUNT_AFTER) {
				if(m.getCriticality() >= OpportunisticDataCachingRouter.COUNT_CRITICALITY_MIN && m.getCriticality() <= OpportunisticDataCachingRouter.COUNT_CRITICALITY_MAX) {
					this.storeCount(m.getId(), -1234); // hack to track that id's latency
				}
			}

			return returns; // it was a request, so no need to cache
		}

		// try to cache now!


		// currently connected NCLs
		List<DTNHost> ncls = new ArrayList<DTNHost>();
		for(Connection con : this.getConnections()) {
			DTNHost node = con.getOtherNode(this.getHost());
			if(node.isNCL()) {
				ncls.add(node);
			}
		}

		// Used for popularity
		String id = m.getId();
		if(!this.timesSeen.containsKey(id)) {
			this.timesSeen.put(id, 0);
		}

		int timesSeenMessage = this.timesSeen.get(id) + 1;
		this.timesSeen.put(id, timesSeenMessage);

		// normally the soldiers and NCLs would re-classify based on some military heuristic. We will do random for this simulation
		m.setCriticality(m.getCriticality() + ThreadLocalRandom.current().nextInt(-1, 2)); // randomly add -1, 0, or 1 to the criticallity

		if(m.getProperty("to_cache") == null) {
			m.addProperty("to_cache", "local");
		}

		 // try to decide who else should cache it based on criticallity
		switch(m.getCriticality()) { // to test for just popularity always ensure class -5, criticality just sort for most critical
			case 0:
				m.updateProperty("to_cache", "all");
				break;
			case -1:
				m.updateProperty("to_cache", "NCLs");
				break;
			case -2:
				if(ncls.size() > 0) {
					m.updateProperty("to_cache", ncls.get(0).toString());
				}
				break;
			case -4:
			case -5: // cache based on popularity
				tryToCacheBasedOnPopularity(m);
		}

		Object property = m.getProperty("to_cache");

		if(property != null) {
			String who = (String)property;
			if(who == "all" || (who == "NCLs" && this.getHost().isNCL()) || who == this.getHost().toString()) {
				this.forceCache(m);
			}
		}

		return returns;
	}

	@Override
	public Message messageTransferred(String id, DTNHost from) {
		Message m = super.messageTransferred(id, from);


		// we want to do re-distribution upon recieving the request if it was too latent
		if (m.getTo() == getHost() && m.getResponseSize() > 0) { // then it was transfered!
			double latency = m.getReceiveTime() - m.getCreationTime();

			double minLatency = -1*m.getCriticality() * 100; // simply allow 100 extra sec for each level of criticality, so 0 for class 0, 100 for class 1, ... 500 for class 5, etc.
			if(latency > minLatency) { // then it was too latent, force cache it.
				forceCache(m);
			}
		}

		return m;
	}

	private void tryCache(Message m) {
		int cacheLeft = this.getRemainingCacheSize();

		if(cacheLeft >= m.getSize()) {
			this.cacheMessage(m);
		}
	}

	private void tryToCacheBasedOnPopularity(Message m) {
		int timesSeenMessage = this.timesSeen.get(m.getId());
		int popularity = timesSeenMessage * m.getHopCount();
		boolean cache = false;
		if(popularity > 0) { // verify we have less popular data to uncache
			for(String key : this.cachedMessages.keySet()) {
				Message cached = this.cachedMessages.get(key);

				int cachedPopularity = this.timesSeen.get(cached.getId()) * m.getHopCount();
				if(popularity > cachedPopularity) {
					cache = true;
					break;
				}
			}
		}

		if(cache) {
			this.forceCache(m);
		}
	}

	private boolean forceCache(Message m) {
		int size = m.getSize();

		if(size > this.cacheSize) {
			return false;
		}

		this.tryCache(m);

		if(!this.hasCachedMessage(m.getId())) { // we have to evict data
			int cacheLeft = this.getRemainingCacheSize();
			while(cacheLeft < size) { // remove the least needed cached message to make room
				Message removed = this.uncacheMessage();
				cacheLeft += removed.getSize();
			}
			// when we get past that loop we have enough space!
			this.cacheMessage(m);
			return true;
		}

		return false;
	}

	private Message uncacheMessage() {
		Message removing = null;

		for(String key : this.cachedMessages.keySet()) {
			Message cached = this.cachedMessages.get(key);

			if(removing == null || removing.getCriticality() < cached.getCriticality()) {
				removing = cached;
			}
		}

		this.cachedMessages.remove(removing);

		this.createNewMessage(removing); // broadcast it in case someone else can cache it, as we are removing it

		return removing;
	}

	// END: The main parts I've added


	@Override
	public void update() {
		super.update();
		if (isTransferring() || !canStartTransfer()) {
			return; // transferring, don't try other connections yet
		}

		// Try first the messages that can be delivered to final recipient
		if (exchangeDeliverableMessages() != null) {
			return; // started a transfer, don't try others (yet)
		}

		// then try any/all message to any/all connection
		this.tryAllMessagesToAllConnections();
	}


	@Override
	public OpportunisticDataCachingRouter replicate() {
		return new OpportunisticDataCachingRouter(this);
	}

}
