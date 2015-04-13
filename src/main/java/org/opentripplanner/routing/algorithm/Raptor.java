package org.opentripplanner.routing.algorithm;

import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jersey.repackaged.com.google.common.collect.Sets;

import com.google.common.collect.Lists;

import flexjson.PathExpression;
import gnu.trove.iterator.TObjectIntIterator;

import org.opentripplanner.routing.core.RoutingRequest;
import org.opentripplanner.routing.core.State;
import org.opentripplanner.routing.core.TraverseModeSet;
import org.opentripplanner.routing.edgetype.PatternDwell;
import org.opentripplanner.routing.edgetype.PatternHop;
import org.opentripplanner.routing.edgetype.PreBoardEdge;
import org.opentripplanner.routing.edgetype.SimpleTransfer;
import org.opentripplanner.routing.edgetype.TransitBoardAlight;
import org.opentripplanner.routing.edgetype.TripPattern;
import org.opentripplanner.routing.graph.Edge;
import org.opentripplanner.routing.graph.Vertex;
import org.opentripplanner.routing.spt.DominanceFunction;
import org.opentripplanner.routing.spt.ShortestPathTree;
import org.opentripplanner.routing.trippattern.FrequencyEntry;
import org.opentripplanner.routing.trippattern.TripTimeSubset;
import org.opentripplanner.routing.vertextype.TransitStop;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Implements the RAPTOR algorithm; see http://research.microsoft.com/pubs/156567/raptor_alenex.pdf */
public class Raptor {
    public RaptorStateStore store;
    public RoutingRequest options;
    
    private static final Logger LOG = LoggerFactory.getLogger(Raptor.class);
    
    private HashSet<TripPattern> markedPatterns = Sets.newHashSet();
    private HashSet<TransitStop> markedStops;
    
    private Map<TripPattern, TripTimeSubset> times;
        
    /** Initialize a RAPTOR router from states at transit stops and the timetables of trips running in the window. */
    /*public Raptor(Collection<State> states, RoutingRequest options, Map<TripPattern, TripTimeSubset> times) {
        // TODO: don't hardwire store type
        store = new PathDiscardingRaptorStateStore();
        this.options = options;
        this.times = times;
        
        for (State state : states) {
        	Vertex v = state.getVertex();
        	
        	if (!(v instanceof TransitStop))
        		throw new IllegalStateException("Initial state is not at transit stop!");
        			
            store.put((TransitStop) v, (int) state.);
            markedPatterns.addAll(options.rctx.graph.index.patternsForStop.get(((TransitStop) v).getStop()));
        }
    }*/
    
    /** Initialize a RAPTOR router from an existing RaptorStateStore, a routing request, and the timetables of active trips */
    public Raptor(RaptorStateStore store, RoutingRequest options, Map<TripPattern, TripTimeSubset> times) {
    	this.options = options;
    	this.store = store;
    	this.times = times;
    	
    	for (TObjectIntIterator<TransitStop> it = store.currentIterator(); it.hasNext();) {
    		it.advance();
    		
    		markedPatterns.addAll(options.rctx.graph.index.patternsForStop.get(it.key().getStop()));
    	}
    }
    
    /** Initialize a RAPTOR router with only a routing request */
    /*
    public Raptor(RoutingRequest options) {
    	this.options = options;
    	this.store = new StatePreservingRaptorStateStore();
    	
    	findInitialStops();
    }
    */
    
    /** Get a state at the destination, for a point-to-point search */
    /*
    public ShortestPathTree getShortestPathTree () {
    	run();
    	return finishSearch();
    }
    */
    
    public void run () {
        // slightly hacky, but proceed here so that prev is fixed and we can modify current.
        store.proceed();
        for (int round = 0; round <= options.maxTransfers; round++) {
            if (!doRound(round == options.maxTransfers))
            	break;
        }
    }
    
    /**
     * perform one round of a RAPTOR search.
     * @return whether this round relaxed any bounds.
     */
    private boolean doRound (boolean isLast) {
    	//LOG.info("Begin RAPTOR round");
        // TODO: filter to routes that are running
        // TODO: implement the rest of the optimizations in the paper, in particular not going past the destination
    	Set<TripPattern> oldMarkedPatterns = markedPatterns;
    	markedPatterns = Sets.newHashSet();
    	markedStops = Sets.newHashSet();
    	
    	//LOG.info("Exploring {} patterns", oldMarkedPatterns.size());
    	
        PATTERNS: for (TripPattern tp : oldMarkedPatterns) {
            STOPS: for (int i = 0; i < tp.stopVertices.length; i++) {
                int time = store.getPrev(tp.stopVertices[i]);
                if (time == Integer.MAX_VALUE)
                    continue STOPS;
                
                propagate(time, tp, i);
            }
        }
        
        // Find all possible transfers
    	if (!isLast) {
    		store.proceed();
    		findTransfers();
    	}
        
        // check if it changed
        return !markedPatterns.isEmpty();
    }
    
    /** Find all the transfers from the last round to this round */
    public void findTransfers () {
        // TODO: don't transfer from things that were not updated this round
        for (TransitStop tstop : markedStops) {        	            
            for (Edge e : tstop.getOutgoing()) {
                if (e instanceof SimpleTransfer) {
                	TransitStop to = (TransitStop) e.getToVertex();
                	
                    if (store.put(to, (int) (store.getPrev(tstop) + e.getDistance() / options.walkSpeed))) {
                        markedPatterns.addAll(options.rctx.graph.index.patternsForStop.get(to.getStop()));	
                    }
                }
            }
        }
    }
    
    /** Propagate a state down a trip pattern */
    public void propagate (int time, TripPattern tripPattern, int stopIndex) {
    	
    	if (!propagateFrequencies(time, tripPattern, stopIndex))
    		propagateSchedules(time, tripPattern, stopIndex);
    }
    
    public boolean propagateFrequencies (int time, TripPattern tripPattern, int stopIndex) {    	
    	// first check for frequency trips
    	boolean foundFrequencyEntry = false;
    	
    	int bestFreqBoardTime = Integer.MAX_VALUE;
    	FrequencyEntry bestFreq = null;
    	
    	for (FrequencyEntry freq : tripPattern.scheduledTimetable.frequencyEntries) {
    		// TODO FIXME !!!: CHECK DATE
    		
    		// we set this here rather than below the time check, because in Analyst we run
    		// RAPTOR many times and we don't want the same patterns switching back and forth
    		// from frequency to scheduled. (or do we?)
    		foundFrequencyEntry = true;
    		
    		// the first stop on the trip must occur between start_time and end_time,
    		// so offset start_time and end_time the appropriate amount to see if it applies.
    		int offsetSecs = freq.tripTimes.getScheduledDepartureTime(stopIndex);
    		
    		// there are no more trips on this frequency entry
    		if (freq.endTime + offsetSecs < time)
    			continue;
    		
    		int boardTime = time + freq.headway;
    		
    		// we have to wait until the service starts running
    		// note that we still figure in the wait; unless we are using exact times there
    		// is no guarantee that first trip left at exactly the start time of the frequency entry,
    		// only within headway_seconds.
    		if (freq.startTime + offsetSecs > time) {
    			boardTime += freq.startTime + offsetSecs - time;
    		}
    		
    		if (boardTime < bestFreqBoardTime) {
    			bestFreqBoardTime = boardTime;
    			bestFreq = freq;
    		}
    	}
    	
    	if (bestFreq == null)
    		return foundFrequencyEntry;
    	
    	int boardOffset = bestFreq.tripTimes.getScheduledDepartureTime(stopIndex);
    	
    	for (int reachedIdx = stopIndex + 1; reachedIdx < bestFreq.tripTimes.getNumStops(); reachedIdx++) {
    		TransitStop v = tripPattern.stopVertices[reachedIdx];
    		// board time already includes headway, if we are computing the worst-case
    		int arrTime = bestFreqBoardTime + bestFreq.tripTimes.getScheduledArrivalTime(reachedIdx) - boardOffset;
    		
    		if (store.put(v, arrTime)) {
    			for (TripPattern tp : options.rctx.graph.index.patternsForStop.get(v.getStop())) {
    				if (tp != tripPattern)
    					markedPatterns.add(tp);
    			}
    			
    			markedStops.add(v);
    		}
    	}
    	
    	return foundFrequencyEntry;
    }
    	
    public void propagateSchedules(int time, TripPattern tripPattern, int stopIndex) {
    	TripTimeSubset tts = times.get(tripPattern);
    	
    	if (tts == null)
    		return;
    	
    	// find the appropriate trip, quickly (uses a binary search)
    	int tripIndex = tts.findTripAfter(stopIndex, time);
    	
    	if (tripIndex == -1)
    		return;
    	
    	for (int reachedIdx = stopIndex + 1; reachedIdx < tripPattern.stopVertices.length; reachedIdx++) {
    		TransitStop v = tripPattern.stopVertices[reachedIdx];
    		int arrTime = tts.getArrivalTime(tripIndex, reachedIdx);
    		
    		if (store.put(v, arrTime)) {
    			for (TripPattern tp : options.rctx.graph.index.patternsForStop.get(v.getStop())) {
    				if (tp != tripPattern)
    					markedPatterns.add(tp);
    			}
    			
    			markedStops.add(v);
    		}
    	}
    }
    
    /** Get an iterator over all the nondominated target states of this RAPTOR search */
    public TObjectIntIterator<TransitStop> iterator () {
        return store.currentIterator();
    }
    
    /*
    private void findInitialStops () {
    	TraverseModeSet oldModes = options.modes;
    	long oldWorstTime = options.worstTime;
    	boolean oldBatch = options.batch;
    	options.modes = options.modes.clone();
    	options.modes.setTransit(false);
    	options.worstTime = options.dateTime + (long) (options.maxWalkDistance / options.walkSpeed);
    	options.batch = true;
    	
    	// routing context already set
    	
    	AStar astar = new AStar();    	
    	ShortestPathTree spt = astar.getShortestPathTree(options);
    	
    	for (TransitStop tstop : options.rctx.graph.index.stopVertexForStop.values()) {
    		State s = spt.getState(tstop);
    		if (s != null) {
    			if (store.put(s))
    	            markedPatterns.addAll(options.rctx.graph.index.patternsForStop.get(((TransitStop) s.getVertex()).getStop()));
    			
    		}
    	}
    	
    	options.modes = oldModes;
    	options.batch = oldBatch;
    	options.worstTime = oldWorstTime;
    }
    
    private ShortestPathTree finishSearch () {
    	Collection<State> states = Lists.newArrayList();
    	    	
    	for (Iterator<State> it = store.currentIterator(); it.hasNext();) {
    		State s = it.next();
    		states.add(s);
     	}
    	
    	TraverseModeSet oldModes = options.modes;
    	options.modes.clone();
    	options.modes.setTransit(false);
    	
    	// add the origin as well
    	states.add(new State(options));
    	
    	AStar astar = new AStar();
    	// TODO this is not efficient
    	ShortestPathTree spt = astar.getShortestPathTree(options, 10, null, states);
    	options.modes = oldModes;
    	return spt;
    }
    */
}