/**
 * TAC AgentWare
 * http://www.sics.se/tac        tac-dev@sics.se
 *
 * Copyright (c) 2001-2005 SICS AB. All rights reserved.
 *
 * SICS grants you the right to use, modify, and redistribute this
 * software for noncommercial purposes, on the conditions that you:
 * (1) retain the original headers, including the copyright notice and
 * this text, (2) clearly document the difference between any derived
 * software and the original, and (3) acknowledge your use of this
 * software in pertaining publications and reports.  SICS provides
 * this software "as is", without any warranty of any kind.  IN NO
 * EVENT SHALL SICS BE LIABLE FOR ANY DIRECT, SPECIAL OR INDIRECT,
 * PUNITIVE, INCIDENTAL OR CONSEQUENTIAL LOSSES OR DAMAGES ARISING OUT
 * OF THE USE OF THE SOFTWARE.
 *
 * -----------------------------------------------------------------
 *
 * Author  : Joakim Eriksson, Niclas Finne, Sverker Janson
 * Created : 23 April, 2002
 * Updated : $Date: 2005/06/07 19:06:16 $
 *       $Revision: 1.1 $
 * ---------------------------------------------------------
 * DummyAgent is a simplest possible agent for TAC. It uses
 * the TACAgent agent ware to interact with the TAC server.
 *
 * Important methods in TACAgent:
 *
 * Retrieving information about the current Game
 * ---------------------------------------------
 * int getGameID()
 *  - returns the id of current game or -1 if no game is currently plaing
 *
 * getServerTime()
 *  - returns the current server time in milliseconds
 *
 * getGameTime()
 *  - returns the time from start of game in milliseconds
 *
 * getGameTimeLeft()
 *  - returns the time left in the game in milliseconds
 *
 * getGameLength()
 *  - returns the game length in milliseconds
 *
 * int getAuctionNo()
 *  - returns the number of auctions in TAC
 *
 * int getClientPreference(int client, int type)
 *  - returns the clients preference for the specified type
 *   (types are TACAgent.{ARRIVAL, DEPARTURE, HOTEL_VALUE, E1, E2, E3}
 *
 * int getAuctionFor(int category, int type, int day)
 *  - returns the auction-id for the requested resource
 *   (categories are TACAgent.{CAT_FLIGHT, CAT_HOTEL, CAT_ENTERTAINMENT
 *    and types are TACAgent.TYPE_INFLIGHT, TACAgent.TYPE_OUTFLIGHT, etc)
 *
 * int getAuctionCategory(int auction)
 *  - returns the category for this auction (CAT_FLIGHT, CAT_HOTEL,
 *    CAT_ENTERTAINMENT)
 *
 * int getAuctionDay(int auction)
 *  - returns the day for this auction.
 *
 * int getAuctionType(int auction)
 *  - returns the type for this auction (TYPE_INFLIGHT, TYPE_OUTFLIGHT, etc).
 *
 * int getOwn(int auction)
 *  - returns the number of items that the agent own for this
 *    auction
 *
 * Submitting Bids
 * ---------------------------------------------
 * void submitBid(Bid)
 *  - submits a bid to the tac server
 *
 * void replaceBid(OldBid, Bid)
 *  - replaces the old bid (the current active bid) in the tac server
 *
 *   Bids have the following important methods:
 *    - create a bid with new Bid(AuctionID)
 *
 *   void addBidPoint(int quantity, float price)
 *    - adds a bid point in the bid
 *
 * Help methods for remembering what to buy for each auction:
 * ----------------------------------------------------------
 * int getAllocation(int auctionID)
 *   - returns the allocation set for this auction
 * void setAllocation(int auctionID, int quantity)
 *   - set the allocation for this auction
 *
 *
 * Callbacks from the TACAgent (caused via interaction with server)
 *
 * bidUpdated(Bid bid)
 *  - there are TACAgent have received an answer on a bid query/submission
 *   (new information about the bid is available)
 * bidRejected(Bid bid)
 *  - the bid has been rejected (reason is bid.getRejectReason())
 * bidError(Bid bid, int error)
 *  - the bid contained errors (error represent error status - commandStatus)
 *
 * quoteUpdated(Quote quote)
 *  - new information about the quotes on the auction (quote.getAuction())
 *    has arrived
 * quoteUpdated(int category)
 *  - new information about the quotes on all auctions for the auction
 *    category has arrived (quotes for a specific type of auctions are
 *    often requested at once).
 * auctionClosed(int auction)
 *  - the auction with id "auction" has closed
 *
 * transaction(Transaction transaction)
 *  - there has been a transaction
 *
 * gameStarted()
 *  - a TAC game has started, and all information about the
 *    game is available (preferences etc).
 *
 * gameStopped()
 *  - the current game has ended
 *
 */

/*
 * Hotel bidding: when deciding how much to bid for a hotel for a client,
 * find the trip for that client with the highest utility that doesn't contain
 * that hotel to determine max price to bid
 */

package se.sics.tac.aw;
import se.sics.tac.util.ArgEnumerator;
import java.util.logging.*;
import java.util.ArrayList;
import java.util.HashMap;

public class Phobos extends AgentImpl {

  private static final Logger log =
    Logger.getLogger(Phobos.class.getName());

  private static final boolean DEBUG = false;

  private float[] prices;
  private float[] previousPrices;
  private float[] submittedPrices;
  private float[] currentFlightPrices;

  // Store a list of the flights that are being monitored for buying
  private HashMap<Integer, Integer> monitorFlights;

  // Store hotel price estimates
  private float[] cheapHotelEstimates = {
    50, 70, 100, 50
  };
  private float[] expensiveHotelEstimates = {
    100, 150, 300, 150
  };

  private ArrayList<Client> clients;

  // unusedItems stores auctionID and quantity. No need to store cost, as it has
  // already been accounted for by the previous client
  private HashMap<Integer, Integer> unusedItems;

  protected void init(ArgEnumerator args) {
    prices = new float[agent.getAuctionNo()];
  }

  // New information about the quotes on the auction (quote.getAuction())
  // has arrived
  public void quoteUpdated(Quote quote) {
    int auction = quote.getAuction();
    int auctionCategory = agent.getAuctionCategory(auction);
    if (auctionCategory == TACAgent.CAT_FLIGHT) {
      currentFlightPrices[auction] = quote.getAskPrice(); // Update currentFlightPrices[]

      // If trend is negative, price going down
      float trend = quote.getAskPrice() - previousPrices[auction];
      if (trend > 0 && monitorFlights.get(auction) != null && monitorFlights.get(auction) > 0) { // Prices are going up, so buy!
        int quantity = monitorFlights.get(auction);
        Bid b = new Bid(auction);
        b.addBidPoint(quantity, 1000);
        agent.submitBid(b);
        
        // Remove the flights from monitoring and allocate them
        monitorFlights.put(auction, 0);
        assignItems(auction, quote.getAskPrice(), quantity);
      }
    }
    else if (auctionCategory == TACAgent.CAT_HOTEL) {
      // TODO: update estimatedHotelPrices[] when quoteUpdated apart from those set to 9999 (closed auctions)
      // Really basic impl uses current price and adds 50 for the estimates
      int day = agent.getAuctionDay(auction) - 1; // Need to subtract 1 as array starts at index 0
      if (agent.getAuctionType(auction) == TACAgent.TYPE_GOOD_HOTEL) {
        if (expensiveHotelEstimates[day] != 9999) {
          expensiveHotelEstimates[day] = quote.getAskPrice() + 50;
        }
      } else {
        if (cheapHotelEstimates[day] != 9999) {
          cheapHotelEstimates[day] = quote.getAskPrice() + 50;
        }
      }

      // int alloc = agent.getAllocation(auction); // Allocation is number of items wanted from this auction
      /* If there are any to be won, and the Hypothetical Quantity Won is less than the amount needed */
      /*
      if (alloc > 0 && quote.hasHQW(agent.getBid(auction)) && quote.getHQW() < alloc) {
        Bid bid = new Bid(auction);
        // Can not own anything in hotel auctions...
        prices[auction] = quote.getAskPrice() + 50;
        bid.addBidPoint(alloc, prices[auction]);
        if (DEBUG) {
          log.finest("submitting bid with alloc=" + agent.getAllocation(auction) + " own=" + agent.getOwn(auction));
        }
        agent.submitBid(bid);
      }
      */
    } else if (auctionCategory == TACAgent.CAT_ENTERTAINMENT) {
      int alloc = agent.getAllocation(auction) - agent.getOwn(auction);
      if (alloc != 0) {
        Bid bid = new Bid(auction);
        if (alloc < 0) { // If we have more than we need
          prices[auction] = 200f - (agent.getGameTime() * 120f) / 720000; // Set a negative price
        } else { // Otherwise, create a bid
          prices[auction] = 50f + (agent.getGameTime() * 100f) / 720000;
        }
        bid.addBidPoint(alloc, prices[auction]);
        if (DEBUG) {
          log.finest("submitting bid with alloc=" + agent.getAllocation(auction) + " own=" + agent.getOwn(auction));
        }
        agent.submitBid(bid);
      }
    }
    // Updated the previous prices array
    previousPrices[auction] = quote.getAskPrice();

    // Recalculate the allocations
    allocateAndBid();
  }

  // TODO: Function should set the allocation tables and place the bids
  private void allocateAndBid() {
    ArrayList<Bid> bids = new ArrayList<Bid>(); // Stores the bids which are placed at the end of the function

    // Clear flights from monitoring
    for (Integer auction : monitorFlights.keySet()) {
      monitorFlights.put(auction, 0);
    }

    // Distribute the unused items among the clients
    assignUnusedItems();

    for (Client c : clients) {
      Trip t = c.getOptimalTrip();

      // Create the appropriate bids for this client taking owned items into account

      // Re-add flights to monitoring if not owned
    }

    // Place the bids
    for (Bid b : bids) {
      agent.submitBid(b);
    }
    
  }

  // TODO: function for smarter assignment of available items, so they go to the
  // client that benefit the most from them first. Used in assignUnusedItems(),
  // auctionClosed(), and quoteUpdated()
  private void assignItems(int auctionNumber, float price, int quantity) {
    for (Client c : clients) {
      if (quantity > 0 && c.needsAuction(auctionNumber)) {
        --quantity;
        c.assignAuctionItem(auctionNumber, price);
      }
    }
    // Place any remaining items in unused items
    if (unusedItems.get(auctionNumber) == null) {
      unusedItems.put(auctionNumber, quantity);
    } else {
      unusedItems.put(auctionNumber, unusedItems.get(auctionNumber) + quantity);
    }
  }

  // Function to assign the items left in unusedItems
  private void assignUnusedItems() {
    // Iterate through the HashMap. At each non-empty value, iterate through
    // clients to see if anyone needs it
    for (Integer k : unusedItems.keySet()) {
      int quantity = unusedItems.get(k);
      if (quantity > 0) {
        unusedItems.put(k, 0); // Gets reset based on usage in assignItems()
        assignItems(k, 1, quantity);
      }
    }
  }

  // New information about the quotes on all auctions for the auction
  // category has arrived (quotes for a specific type of auctions are
  // often requested at once).
  public void quoteUpdated(int auctionCategory) {
    log.fine("All quotes for " + agent.auctionCategoryToString(auctionCategory) + " has been updated");
  }

  // There are TACAgent have received an answer on a bid query/submission
  // (new information about the bid is available)
  public void bidUpdated(Bid bid) {
    log.fine("Bid Updated: id=" + bid.getID() + " auction=" + bid.getAuction() + " state=" + bid.getProcessingStateAsString());
    log.fine("       Hash: " + bid.getBidHash());
  }

  // The bid has been rejected (reason is bid.getRejectReason())
  public void bidRejected(Bid bid) {
    log.warning("Bid Rejected: " + bid.getID());
    log.warning("      Reason: " + bid.getRejectReason() + " (" + bid.getRejectReasonAsString() + ')');
  }

  // The bid contained errors (error represent error status - commandStatus)
  public void bidError(Bid bid, int status) {
    log.warning("Bid Error in auction " + bid.getAuction() + ": " + status + " (" + agent.commandStatusToString(status) + ')');
  }

  // A TAC game has started, and all information about the
  // game is available (preferences etc).
  public void gameStarted() {
    log.fine("Game " + agent.getGameID() + " started!");
    previousPrices = new float[agent.getAuctionNo()]; // Reset the previous prices array
    submittedPrices = new float[agent.getAuctionNo()]; // Reset submitted prices array
    currentFlightPrices = new float[agent.getAuctionNo()]; // Reset flight prices array
    unusedItems = new HashMap<Integer, Integer>(); // Reset unusedItems HashMap
    monitorFlights = new HashMap<Integer, Integer>(); // Reset the flight monitoring
    clients = new ArrayList<Client>();
    calculateAllocation();
    sendBids();
  }

  // The current game has ended
  public void gameStopped() {
    log.fine("Game Stopped!");
  }

  // The auction with id "auction" has closed
  public void auctionClosed(int auction) {
    // When a hotel auction closes, change the estimated price to 9999 to
    // prevent other clients using a trip with it
    if (agent.getAuctionCategory(auction) == TACAgent.CAT_HOTEL) {
      int day = agent.getAuctionDay(auction) - 1; // Need to subtract 1 as array starts at index 0
      if (agent.getAuctionType(auction) == TACAgent.TYPE_GOOD_HOTEL) {
        expensiveHotelEstimates[day] = 9999;
      } else {
        cheapHotelEstimates[day] = 9999;
      }

      // Assign the hotels to clients that want them
      int own = agent.getOwn(auction);
      if (own > 0) {
        assignItems(auction, submittedPrices[auction], own);
      }
    }

    // Check all clients to see if any are fulfilled and free up unused items
    for (Client c : clients) {
      c.checkIfFulfilled();
    }
    assignUnusedItems(); // Assign the left over items
    log.fine("*** Auction " + auction + " closed!");
  }

  // Sends initial bids
  private void sendBids() {
    for (int i = 0, n = agent.getAuctionNo(); i < n; i++) {
      int alloc = agent.getAllocation(i) - agent.getOwn(i);
      float price = -1f;
      switch (agent.getAuctionCategory(i)) {
        /*
        case TACAgent.CAT_FLIGHT:
          if (alloc > 0) {
            price = 1000;
          }
          break;
        case TACAgent.CAT_HOTEL:
          if (alloc > 0) {
            price = 200;
            prices[i] = 200f;
          }
          break;
          */
        case TACAgent.CAT_ENTERTAINMENT:
          if (alloc < 0) {
            price = 200;
            prices[i] = 200f;
          } else if (alloc > 0) {
            price = 50;
            prices[i] = 50f;
          }
          break;
        default:
          break;
      }
      if (price > 0) {
        Bid bid = new Bid(i);
        bid.addBidPoint(alloc, price);
        if (DEBUG) {
          log.finest("submitting bid with alloc=" + agent.getAllocation(i) + " own=" + agent.getOwn(i));
        }
        agent.submitBid(bid);
      }
    }
  }

  // Sets the 'allocation' to the required flights, hotels, and entertainment.
  // An allocation is a item that we need to provide to the client.
  private void calculateAllocation() {
    // Loop through for each of the 8 clients
    for (int i = 0; i < 8; i++) {
      
      int inFlight = agent.getClientPreference(i, TACAgent.ARRIVAL);
      int outFlight = agent.getClientPreference(i, TACAgent.DEPARTURE);
      int hotel = agent.getClientPreference(i, TACAgent.HOTEL_VALUE);
      int type;

      /*
      // Get the flight preferences auction and remember that we are
      // going to buy tickets for these days. (inflight=1, outflight=0)
      int auction = agent.getAuctionFor(TACAgent.CAT_FLIGHT, TACAgent.TYPE_INFLIGHT, inFlight);
      agent.setAllocation(auction, agent.getAllocation(auction) + 1);
      auction = agent.getAuctionFor(TACAgent.CAT_FLIGHT, TACAgent.TYPE_OUTFLIGHT, outFlight);
      agent.setAllocation(auction, agent.getAllocation(auction) + 1);

      // If the client's hotel_bonus is greater than 70 we will select the
      // expensive hotel (type = 1)
      if (hotel > 70) {
        type = TACAgent.TYPE_GOOD_HOTEL;
      } else {
        type = TACAgent.TYPE_CHEAP_HOTEL;
      }
      // Allocate a hotel night for each day that the client stays
      for (int d = inFlight; d < outFlight; d++) {
        auction = agent.getAuctionFor(TACAgent.CAT_HOTEL, type, d);
        log.finer("Adding hotel for day: " + d + " on " + auction);
        agent.setAllocation(auction, agent.getAllocation(auction) + 1);
      }
      */
      // Add the client to the ArrayList. Allocations will be dealt with later
      clients.add(new Client(i));

      // Not sure what to do about entertainment yet
      // Allocate a different entertainment for each day the client stays
      int eType = -1;
      while((eType = nextEntType(i, eType)) > 0) {
        int auction = bestEntDay(inFlight, outFlight, eType);
        log.finer("Adding entertainment " + eType + " on " + auction);
        agent.setAllocation(auction, agent.getAllocation(auction) + 1);
      }
    }
  }

  private int bestEntDay(int inFlight, int outFlight, int type) {
    for (int i = inFlight; i < outFlight; i++) {
      int auction = agent.getAuctionFor(TACAgent.CAT_ENTERTAINMENT, type, i);
      if (agent.getAllocation(auction) < agent.getOwn(auction)) {
        return auction;
      }
    }
    // If no left, just take the first...
    return agent.getAuctionFor(TACAgent.CAT_ENTERTAINMENT, type, inFlight);
  }

  private int nextEntType(int client, int lastType) {
    int e1 = agent.getClientPreference(client, TACAgent.E1);
    int e2 = agent.getClientPreference(client, TACAgent.E2);
    int e3 = agent.getClientPreference(client, TACAgent.E3);

    // At least buy what each agent wants the most!!!
    if ((e1 > e2) && (e1 > e3) && lastType == -1)
      return TACAgent.TYPE_ALLIGATOR_WRESTLING;
    if ((e2 > e1) && (e2 > e3) && lastType == -1)
      return TACAgent.TYPE_AMUSEMENT;
    if ((e3 > e1) && (e3 > e2) && lastType == -1)
      return TACAgent.TYPE_MUSEUM;
    return -1;
  }



  // -------------------------------------------------------------------
  // Only for backward compability
  // -------------------------------------------------------------------

  public static void main (String[] args) {
    TACAgent.main(args);
  }

  /*
   * Clients will contain multiple possible trips that will be valid for them,
   * each of which will have an associated utility. Clients will also hold
   * the auctions won, and the prices paid for those items, which trips can
   * factor into the utility
   */
  public class Client {
    private int clientNumber;
    private int preferredInFlight;
    private int preferredOutFlight;
    private int hotelBonus;
    private ArrayList<Trip> possibleTrips;
    private float[] assignedAuctions; // Stores the price of all aucitons won for this client

    public Client(int clientNumber) {
      // Initialise vars
      possibleTrips = new ArrayList<Trip>();
      assignedAuctions = new float[agent.getAuctionNo()];

      this.clientNumber = clientNumber;
      // Use client number to get and store preferences
      this.preferredInFlight = agent.getClientPreference(clientNumber, TACAgent.ARRIVAL);
      this.preferredOutFlight = agent.getClientPreference(clientNumber, TACAgent.DEPARTURE);
      this.hotelBonus = agent.getClientPreference(clientNumber, TACAgent.HOTEL_VALUE);

      buildClientTrips(); // Create permutations for the possible client trips
    }

    private void buildClientTrips() {
      for (int i = preferredInFlight; i < preferredOutFlight; ++i) {
        for (int k = preferredOutFlight; k > i; --k) {
          // Add a trip with the expensive hotel
          possibleTrips.add(new Trip(this, i, k, TACAgent.TYPE_GOOD_HOTEL));
          // and the cheap hotel
          possibleTrips.add(new Trip(this, i, k, TACAgent.TYPE_CHEAP_HOTEL));
        }
      }
    }

    // An auction has been won for an item this client wanted, so add it to their list
    public void assignAuctionItem(int auctionNumber, float price) {
      // Sometimes hotels can be won for 0, so just set to 1 so that system knows it is owned
      if (price == 0) {
      	price = 1;
      }
      assignedAuctions[auctionNumber] = price;
      log.fine("*** Client " + clientNumber + " has been allocated Auction ID " + auctionNumber + " sold at " + price);
    }

    // Return the trip with the highest utility
    public Trip getOptimalTrip() {
      Trip currentHighest = possibleTrips.get(0);
      for (Trip t : possibleTrips) {
        if (t.getUtility() > currentHighest.getUtility()) {
          currentHighest = t;
        }
      }
      return currentHighest;
    }

    // Check if the optimal trip requires this auction and whether the client
    // already owns it
    public boolean needsAuction(int auctionNumber) {
      Trip t = getOptimalTrip();
      return (assignedAuctions[auctionNumber] == 0 && t.getAuctions().contains(auctionNumber));
    }

    // function checks if client trip has been fulfilled. If so, removes
    // it from clients ArrayList and leaves unused hotels/flights in some structure
    // at the agent level
    public void checkIfFulfilled() {
      // Get the optimal trip, and check if client owns all that is needed for it
      boolean ownEverything = true;
      ArrayList<Integer> auctions = getOptimalTrip().getAuctions();

      for (Integer i : auctions) {
        if (assignedAuctions[i] == 0) { // If we don't own this item
          ownEverything = false;
        }
      }

      if (ownEverything) {
        // Add all the unused hotels/flights to the unusedItems for other clients to use
        for (int i = 0; i < assignedAuctions.length; ++i) {
          if (!auctions.contains(i)) { // Add this item to unusedItems
            if (unusedItems.get(i) == null) {
              unusedItems.put(i, 1);
            } else {
              unusedItems.put(i, unusedItems.get(i) + 1);
            }
          }
        }

        // Remove client from list
        clients.remove(this);
        log.fine("*** Client " + clientNumber + " has been fulfilled.");
      }
    }

    public int getInFlight() { return preferredInFlight; }
    public int getOutFlight() { return preferredOutFlight; }
    public int getHotelBonus() { return hotelBonus; }
    public float[] getAssignedAuctions() { return assignedAuctions; }

  } // Client

  public class Trip {

    private Client client;
    private int inFlight;
    private int outFlight;
    private int hotelType;
    private ArrayList<Integer> auctions; // A list of the auctions used in this trip
    private float[] estimatedHotelPrices;

    public Trip(Client c, int inFlight, int outFlight, int hotelType) {
      this.client = client;
      this.inFlight = inFlight;
      this.outFlight = outFlight;
      this.hotelType = hotelType;

      if (hotelType == TACAgent.TYPE_GOOD_HOTEL) {
        estimatedHotelPrices = expensiveHotelEstimates;
      } else {
        estimatedHotelPrices = cheapHotelEstimates;
      }

      // Add all the auction IDs used by trip to auctions ArrayList
      auctions.add(agent.getAuctionFor(TACAgent.CAT_FLIGHT, TACAgent.TYPE_INFLIGHT, inFlight)); // InFlight
      auctions.add(agent.getAuctionFor(TACAgent.CAT_FLIGHT, TACAgent.TYPE_OUTFLIGHT, outFlight)); //OutFlight
      for (int i = inFlight; i < outFlight; ++i) {
      	auctions.add(agent.getAuctionFor(TACAgent.CAT_HOTEL, hotelType, i));
      }
    }

    private float calculateUtility() {
      float hotelCost = 0;
      float result = 0;
      // Get the clients preferred dates and hotel bonus
      int preferredInFlight = client.getInFlight();
      int preferredOutFlight = client.getOutFlight();
      float hotelBonus = client.getHotelBonus();

      // Also get the items owned by the client, and the prices paid
      float[] clientCosts = client.getAssignedAuctions();

      // Calculate the penalty when using these flight dates
      float travelPenalty = (inFlight - preferredInFlight) * 100;
      travelPenalty += (preferredOutFlight - outFlight) * 100;

      // Add up the expected cost of flights. If flights already owned by client
      // use the price paid
      float flightCost = 0;
      int auction = agent.getAuctionFor(TACAgent.CAT_FLIGHT, TACAgent.TYPE_INFLIGHT, inFlight);
      if (clientCosts[auction]  > 0) {
      	flightCost += clientCosts[auction];
      } else {
      	flightCost += currentFlightPrices[auction];
      }

      auction = agent.getAuctionFor(TACAgent.CAT_FLIGHT, TACAgent.TYPE_OUTFLIGHT, outFlight);
      if (clientCosts[auction]  > 0) {
        flightCost += clientCosts[auction];
      } else {
        flightCost += currentFlightPrices[auction];
      }

      // Add up the expected cost of these hotel rooms
      // If the client owns that hotel room, use the price paid. Otherwise, use
      // the estimated price
      for (int i = inFlight; i < outFlight; ++i) {
      	// Need to get auction number to check if client owns hotel
      	auction = agent.getAuctionFor(TACAgent.CAT_HOTEL, hotelType, i);
      	if (clientCosts[auction] > 0) { // If the client already owns the hotel
      	  hotelCost += clientCosts[auction];
      	} else {// Don't own the hotel, use the estimated price
      	  hotelCost += estimatedHotelPrices[i];
      	}
      }

      // Negate the hotel bonus if using the cheap hotel
      // TODO: Check if hotel bonus applies to every day in the trip
      if (hotelType != TACAgent.TYPE_GOOD_HOTEL) {
        hotelBonus = 0;
      }

      // Ideally we'd have something about the entertainment here, but I have
      // no idea what to do with that. Maybe Ryan can add something?

      // Concept of subtracting cost of items bought, but not used in this trip
      // For every item in clientCosts, checks if auction is used in this trip. If not,
      // it adds it to the cost of the trip
      float alreadyBoughtCost = 0;
      for (int i = 0; i < clientCosts.length; ++i) {
      	if (i > 0 && !auctions.contains(i)) {
      		alreadyBoughtCost += clientCosts[i];
      	}
      }

      // Calculate the overall utility of this trip
      return 1000 - travelPenalty - flightCost - hotelCost + hotelBonus - alreadyBoughtCost;
    }
 
    // Method to return whether a hotel is used in this trip or not. Will be used
    // to delete trip if auction closes for a hotel this trip needed, and none are
    // owned by the client
    public boolean containsHotel(int auctionNumber) { return auctions.contains(auctionNumber); }

    // Other getters
    public float getUtility() { return calculateUtility(); }
    public ArrayList<Integer> getAuctions() { return auctions; }
    public int getInFlight() { return inFlight; }
    public int getOutFlight() { return outFlight; }
    public int getHotelType() { return hotelType; }

  } // Trip

} // DummyAgent