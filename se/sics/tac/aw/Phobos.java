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
    Logger.getLogger(DummyAgent.class.getName());

  private static final boolean DEBUG = false;

  private float[] prices;

  // Store hotel price estimates
  private float[] cheapHotelEstimates = {
    50, 70, 100, 50
  };
  private float[] expensiveHotelEstimates = {
    100, 150, 300, 150
  };

  private ArrayList<Client> clients;

  // TODO: Need some structure to store purchased hotels/flights that are no longer needed

  protected void init(ArgEnumerator args) {
    prices = new float[agent.getAuctionNo()];
  }

  // New information about the quotes on the auction (quote.getAuction())
  // has arrived
  public void quoteUpdated(Quote quote) {
    int auction = quote.getAuction();
    int auctionCategory = agent.getAuctionCategory(auction);
    if (auctionCategory == TACAgent.CAT_HOTEL) {
      int alloc = agent.getAllocation(auction); // Allocation is number of items wanted from this auction
      /* If there are any to be won, and the Hypothetical Quantity Won is less than the amount needed */
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
    log.fine("*** Auction " + auction + " closed!");
  }

  // Sends initial bids
  private void sendBids() {
    for (int i = 0, n = agent.getAuctionNo(); i < n; i++) {
      int alloc = agent.getAllocation(i) - agent.getOwn(i);
      float price = -1f;
      switch (agent.getAuctionCategory(i)) {
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

  public class Client {
    private int clientNumber;
    private int preferredInFlight;
    private int preferredOutFlight;
    private int hotelBonus;
    private ArrayList<Trip> possibleTrips;
    private float[] assignedAuctions; // Stores the price of all aucitons won for this client
    // TODO : Need some structure for price paid on items owned, with methods for trip to access

    public Client(int clientNumber) {
      // Initialise vars
      possibleTrips = new ArrayList<Trip>();
      ownedHotels = new boolean[5];

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

    public void assignAuctionItem(int auctionNumber, float price) {
      assignedAuctions[auctionNumber] = price;
      // TODO: Update the price for all the trips to the price paid
    }

    // TODO: Add method when hotel auction closes. If no rooms owned, delete trips
    // using that hotel from possibleTrips ArrayList

    public void updateTripCosts(int auctionNumber, float price) {
      // TODO: Don't update if it's under owned hotels or flights
      for (Trip t : possibleTrips) {
        t.updateCosts(auctionNumber, price);
      }
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

    // TODO: function checks if client trip has been fulfilled. If so, removes
    // it from clients ArrayList and leaves unused hotels/flights in some structure
    // at the agent level
    public void checkIfFulfilled() {

    }

    public int getInFlight() { return preferredInFlight; }
    public int getOutFlight() { return preferredOutFlight; }
    public int getHotelBonus() { return hotelBonus; }
    public float[] getAssignedAuctions() { return assignedAuctions; }

  } // Client

  public class Trip {

    private Client client;
    private float utility;
    private int inFlight;
    private int outFlight;
    private int hotelType;
    private float[] hotelPrices;
    private float inFlightPrice;
    private float outFlightPrice;

    public Trip(Client c, int inFlight, int outFlight, int hotelType) {
      this.client = client;
      this.inFlight = inFlight;
      this.outFlight = outFlight;
      this.hotelType = hotelType;

      if (hotelType == TACAgent.TYPE_GOOD_HOTEL) {
        hotelPrices = expensiveHotelEstimates;
      } else {
        hotelPrices = cheapHotelEstimates;
      }

      inFlightPrice = 0;
      outFlightPrice = 0;

      this.utility = calculateUtility();
    }

    // Method to return whether a hotel is used in this trip or not
    public boolean tripContainsHotel(int auctionNumber) {
      for (int i = inFlight; i < outFlight ++i) {
        int auction = agent.getAuctionFor(TACAgent.CAT_HOTEL, hotelType, i);
        if (auctionNumber == auction) {
          return true;
        }
      }
      return false;
    }

    // An update has been pushed about the costs for this trip. Auction number
    // isn't the best way to do this, but can't think of anything else
    public void updateCosts(int auctionNumber, float price) {
      // Check the auctionNumber applies to one of the items on this trip
      // If so, update that cost
      int auction = agent.getAuctionFor(TACAgent.CAT_FLIGHT, TACAgent.TYPE_INFLIGHT, inFlight);
      if (auctionNumber == auction) {
        inFlightPrice = price; // inFlight price has updated
      }

      auction = agent.getAuctionFor(TACAgent.CAT_FLIGHT, TACAgent.TYPE_OUTFLIGHT, outFlight);
      if (auctionNumber == auction) {
        outFlightPrice = price; // inFlight price has updated
      }

      // Now check the auctions for all the hotels
      for (int i = inFlight; i < outFlight; ++i) {
        auction = agent.getAuctionFor(TACAgent.CAT_HOTEL, hotelType, i);
        if (auctionNumber == auction) {
          hotelPrices[i] = price;
        }
      }

      calculateUtility(); // Recalcuate the utility with the new prices
    }

    private float calculateUtility() {
      float[] estimatedHotelPrices;
      float hotelCost = 0;
      float result = 0;
      // Get the clients preferred dates and hotel bonus
      int preferredInFlight = client.getInFlight();
      int preferredOutFlight = client.getOutFlight();
      float hotelBonus = client.getHotelBonus();

      // Calculate the penalty when using these flight dates
      float travelPenalty1 = (inFlight - preferredInFlight) * 100;
      float travelPenalty2 = (preferredOutFlight - outFlight) * 100;

      // Add up the expected cost of these hotel rooms
      for (int i = inFlight; i < outFlight; ++i) {
        hotelCost += hotelPrices[i];
      }

      // Negate the hotel bonus if using the cheap hotel
      if (hotelType != TACAgent.TYPE_GOOD_HOTEL) {
        hotelBonus = 0;
      }

      // Ideally we'd have something about the entertainment here, but I have
      // no idea what to do with that. Maybe Ryan can add something?

      // TODO: Add concept of subtracting cost of items bought, but not used in this trip

      // Calculate the overall utility of this trip
      return 1000 - travelPenalty1 - travelPenalty2 - inFlightPrice - outFlightPrice - hotelCost + hotelBonus;
    }

    public float getUtility() { return utility; }
    public int getInFlight() { return inFlight; }
    public int getOutFlight() { return outFlight; }
    public int getHotelType() { return hotelType; }

  } // Trip

} // DummyAgent