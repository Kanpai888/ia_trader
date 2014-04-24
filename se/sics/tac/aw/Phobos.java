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

package se.sics.tac.aw;
import se.sics.tac.util.ArgEnumerator;
import java.util.logging.*;
import java.util.ArrayList;
import java.util.HashMap;

public class Phobos extends AgentImpl {

  private static final Logger log =
    Logger.getLogger(Phobos.class.getName());

  private static final boolean DEBUG = false;

  // Min bonus threshold is $23.5 ($50 hotel bonus / 4 days)
  // Max bonus threshold is $150 ($150 hotel bonus / 1 day)
  // At thresholds above $37.5, the agent will never choose TT for a client that wants to 
  // stay for 4 days ($150 hotel bonus / 4 days = $37.5)
  private static final int HOTEL_BONUS_THRESHOLD = 30;

  private float[] prices;
  private float[] previousPrices;

  // Array to show the difference between the current and last price of auctions
  private float[] trends;

  // HashMap stores auctions of flights that should be bought when price is low
  // and quantities of them
  private HashMap<Integer, Integer> buyFlights;

  // Vars used to store return flight price movements
  private float[][] flightPrices;
  private int flightPriceCounter;
  private int flightPriceDay;

  private Client[] clients;
  private ArrayList<Integer> closedAuctions;

  protected void init(ArgEnumerator args) {
    prices = new float[agent.getAuctionNo()];
    previousPrices = new float[agent.getAuctionNo()];
    trends = new float[agent.getAuctionNo()];
    buyFlights = new HashMap<Integer, Integer>();

    flightPrices = new float[4][54];
    flightPriceCounter = 0;
    flightPriceDay = 0;

    clients = new Client[8];
    closedAuctions = new ArrayList<Integer>();
  }

  // New information about the quotes on the auction (quote.getAuction())
  // has arrived
  public void quoteUpdated(Quote quote) {
    int auction = quote.getAuction();
    int auctionCategory = agent.getAuctionCategory(auction);
    int auctionType = agent.getAuctionType(auction);
    if (auctionCategory == TACAgent.CAT_FLIGHT && auctionType == TACAgent.TYPE_INFLIGHT) {
      int alloc = agent.getAllocation(auction) - agent.getOwn(auction);
      // Work out the price trend using the price from the last round
      // If trends[auction] is negative, price has gone *DOWN*
      // if it is positive, price has gone *UP*      
      trends[auction] = quote.getAskPrice() - previousPrices[auction];
      previousPrices[auction] = quote.getAskPrice();

      if (alloc > 0 && trends[auction] > 0 && agent.getGameTime() > 15000) {
        // Price is going up after initial set, so buy
        Bid bid = new Bid(auction);
        bid.addBidPoint(alloc, 1000);
        agent.submitBid(bid);
        log.fine("*** Submitted bid for inflight at price " + quote.getAskPrice() + 
          " with trend " + trends[auction]);
      }

      // Failsafe, in case the price has never gone up, buy with 20 seconds left
      if (agent.getGameTimeLeft() < 20000 && alloc > 0) {
        Bid bid = new Bid(auction);
        bid.addBidPoint(alloc, 1000);
        agent.submitBid(bid);
      }
    }

    if (auctionCategory == TACAgent.CAT_FLIGHT && auctionType == TACAgent.TYPE_OUTFLIGHT) {
      
      int alloc = agent.getAllocation(auction) - agent.getOwn(auction);
      trends[auction] = quote.getAskPrice() - previousPrices[auction];
      previousPrices[auction] = quote.getAskPrice();

      if (alloc > 0) { // If we need this flight
        if (trends[auction] > 0 && buyFlights.get(auction) > 0) { 
          // The price is rising and the auction is in the buyFlights HashMap
          // so create a bid for them
          Bid bid = new Bid(auction);
          bid.addBidPoint(buyFlights.get(auction), 1000);
          agent.submitBid(bid);

          buyFlights.put(auction, 0); // Reset buyFlights HashMap for that auction
        }
      }

      // Track the prices for all the flights anyway for checking in the log
      if (flightPriceDay == 4) {
        flightPriceDay = 0;
        flightPriceCounter++;
      }
      flightPrices[flightPriceDay++][flightPriceCounter] = quote.getAskPrice();

      // Just a basic implementation to ensure the agent makes valid trips while
      // working on real impl.
      // If 20 seconds are left and the flights still aren't bought, buy them
      if (agent.getGameTimeLeft() < 20000 && alloc > 0) {
        Bid bid = new Bid(auction);
        bid.addBidPoint(alloc, 1000);
        agent.submitBid(bid);
      }
    }
    if (auctionCategory == TACAgent.CAT_HOTEL) {
      int alloc = agent.getAllocation(auction); // Allocation is number of items wanted from this auction
      /* If there are any to be won, and the Hypothetical Quantity Won is less than the amount needed */
      if (alloc > 0 && quote.hasHQW(agent.getBid(auction)) && quote.getHQW() < alloc) {
        Bid bid = new Bid(auction);
        // Can not own anything in hotel auctions...
        prices[auction] = quote.getAskPrice() + 50;
        bid.addBidPoint(alloc, prices[auction]);
        if (DEBUG) {
          // log.finest("submitting bid with alloc=" + agent.getAllocation(auction) + " own=" + agent.getOwn(auction));
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
          // log.finest("submitting bid with alloc=" + agent.getAllocation(auction) + " own=" + agent.getOwn(auction));
        }
        agent.submitBid(bid);
      }
    }
  }

  // New information about the quotes on all auctions for the auction
  // category has arrived (quotes for a specific type of auctions are
  // often requested at once).
  public void quoteUpdated(int auctionCategory) {
    //log.fine("All quotes for " + agent.auctionCategoryToString(auctionCategory) + " has been updated");
  }

  // There are TACAgent have received an answer on a bid query/submission
  // (new information about the bid is available)
  public void bidUpdated(Bid bid) {
    // log.fine("Bid Updated: id=" + bid.getID() + " auction=" + bid.getAuction() + " state=" + bid.getProcessingStateAsString() + " stateID=" + bid.getProcessingState());
    // log.fine("       Hash: " + bid.getBidHash());
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

    calculateAllocation();
    sendBids();
  }

  // The current game has ended
  public void gameStopped() {
    // Output the list of flight prices
    // log.fine("**************** Outputting Flight Prices ****************");
    // log.fine("Update\tDay 1\tDay 2\tDay 3\tDay 4");

    // for (int i = 0; i < 54; ++i) {
    //   log.fine(i + " \t" + flightPrices[0][i] + "\t" + flightPrices[1][i] + "\t" + flightPrices[2][i] + "\t" + flightPrices[3][i]);
    // }

    // Reset flight logging vars
    flightPriceCounter = 0;
    flightPriceDay = 0;

    log.fine("Game Stopped!");
  }

 


  

  // The auction with id "auction" has closed
  public void auctionClosed(int auction) {
    log.fine("*** Auction " + auction + " closed!");
    closedAuctions.add(auction);

    // Handle hotel auction closing
    if(agent.getAuctionCategory(auction) == TACAgent.CAT_HOTEL){
      int auctionDay = agent.getAuctionDay(auction);
      int hotelType = agent.getAuctionType(auction);
      int auctionOwn = agent.getOwn(auction);
      int ownedAvailible = auctionOwn;

      log.fine("A hotel auction no-"+auction+" has closed");
      log.fine("It was type "+hotelType);
      log.fine("We won "+auctionOwn);

      // Check which allocations are not feasible
      for (int i = 0; i < 8; i++) {
        int prefInFlight = agent.getClientPreference(i, TACAgent.ARRIVAL);
        int prefOutFlight = agent.getClientPreference(i, TACAgent.DEPARTURE);

        // We ignore clients that are already sorted with hotel arrangements
        if(clients[i].hasHotelFulfilled()){
          continue;
        }

        // Check if this breaks any prefered travel plans
        if(clients[i].hotelWanted(auctionDay, hotelType)){

          // Check if the hotel is availible for this client
          if(ownedAvailible == 0){
            // Client's allocated trip is not feasible as we do not own enough hotels

            // Check if the client already has any hotels booked
            // if(!clients[i].hasOwnedHotelAllocation()){
              // Check if other hotel is feasible

              // Remember to change the allocation table
              // Remember to hold on bidding on hotels that are no longer in
              // the allocation table

            // }else{
              // Shorten trip
              // Remember to change the allocation table
              clients[i].shortenTrip(auctionDay);
            // }
          }else{
            clients[i].addOwnedHotelAllocation(auctionDay);
            ownedAvailible--; 
          }

          // Check if there is a newly completed trip
          if(clients[i].hasHotelFulfilled()){
            // Buy flight
            clientHotelFulfilled(i);
          }
        }
        
        

        // Allocate a hotel night for each day that the client stays
        // for(int d = inFlight; d < outFlight; d++) {
        //   auction = agent.getAuctionFor(TACAgent.CAT_HOTEL, type, d);
        //   log.finer("Adding hotel for day: " + d + " on " + auction);
        //   agent.setAllocation(auction, agent.getAllocation(auction) + 1);
        // }
      }
      
    }
  }

  // Sends initial bids
  private void sendBids() {
    for (int i = 0, n = agent.getAuctionNo(); i < n; i++) {
      int alloc = agent.getAllocation(i) - agent.getOwn(i);
      float price = -1f;
      switch (agent.getAuctionCategory(i)) {
        case TACAgent.CAT_FLIGHT:
          /*
          if (alloc > 0) {
            price = 1000;
          }
          */
          if (agent.getAuctionType(i) == TACAgent.TYPE_INFLIGHT) {
            /*
            if (alloc > 0) {
              price = 1000;
            }
            */
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
      int hotelBonus = agent.getClientPreference(i, TACAgent.HOTEL_VALUE);
      int type;

      // Get the flight preferences auction and remember that we are
      // going to buy tickets for these days. (inflight=1, outflight=0)
      int auction = agent.getAuctionFor(TACAgent.CAT_FLIGHT, TACAgent.TYPE_INFLIGHT, inFlight);
      agent.setAllocation(auction, agent.getAllocation(auction) + 1);
      auction = agent.getAuctionFor(TACAgent.CAT_FLIGHT, TACAgent.TYPE_OUTFLIGHT, outFlight);
      agent.setAllocation(auction, agent.getAllocation(auction) + 1);

      buyFlights.put(auction, 0);

      // Check the value of bonus for each day of stay and compare to a threshold
      // expensive hotel (type = 1)
      int stayDuration = outFlight - inFlight;
      if (hotelBonus / stayDuration > HOTEL_BONUS_THRESHOLD) {
        type = TACAgent.TYPE_GOOD_HOTEL;
      } else {
        type = TACAgent.TYPE_CHEAP_HOTEL;
      }
      // Keeping track of which client should be allocated the good hotel
      clients[i] = new Client(i, type, inFlight, outFlight);
      // Allocate a hotel night for each day that the client stays
      for (int d = inFlight; d < outFlight; d++) {
        auction = agent.getAuctionFor(TACAgent.CAT_HOTEL, type, d);
        log.finer("Adding hotel for day: " + d + " on " + auction);
        agent.setAllocation(auction, agent.getAllocation(auction) + 1);
      }

      // Allocate a different entertainment for each day the client stays
      int eType = -1;
      while((eType = nextEntType(i, eType)) > 0) {
        auction = bestEntDay(inFlight, outFlight, eType);
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

  private void clientHotelFulfilled(int clientNo) {
    int outFlight = agent.getClientPreference(clientNo, TACAgent.DEPARTURE);
    int auction = agent.getAuctionFor(TACAgent.CAT_FLIGHT, TACAgent.TYPE_OUTFLIGHT, outFlight);

    // Buy the flight if the price is currently rising, otherwise wait and
    // monitor as usual
    if (trends[auction] > 0) { // The price is going up
      Bid bid = new Bid(auction);
      bid.addBidPoint(1, 1000);
      agent.submitBid(bid);
    } else {
      // The price is going down, so add it to the HashMap and buy at
      // lowest price
      buyFlights.put(auction, buyFlights.get(auction) + 1);
    }
  }

  // NEED TO CHANGE, trips could be shortended from either start or end day
  // day is a value from 1 to 4
  private void clientTripShortend(int clientNo, int day) {
    // Change the allocation table
    int outFlight = agent.getClientPreference(clientNo, TACAgent.DEPARTURE);
    int auction = agent.getAuctionFor(TACAgent.CAT_FLIGHT, TACAgent.TYPE_OUTFLIGHT, outFlight);
    agent.setAllocation(auction, agent.getAllocation(auction) - 1);
    auction = agent.getAuctionFor(TACAgent.CAT_FLIGHT, TACAgent.TYPE_OUTFLIGHT, day);
    agent.setAllocation(auction, agent.getAllocation(auction) + 1);

    // Buy the flight if the price is currently rising, otherwise wait and
    // monitor as usual
    if (trends[auction] > 0) { // The price is going up
      Bid bid = new Bid(auction);
      bid.addBidPoint(1, 1000);
      agent.submitBid(bid);
    } else {
      // The price is going down, so add it to the HashMap and buy at
      // lowest price
      if (buyFlights.get(auction) != null) {
        buyFlights.put(auction, buyFlights.get(auction) + 1);
      } else {
        buyFlights.put(auction, 1);
      }
    }
  }



  // -------------------------------------------------------------------
  // Only for backward compability
  // -------------------------------------------------------------------

  public static void main (String[] args) {
    TACAgent.main(args);
  }

  // Helper class
  public class Client{

    private int clientID;
    private ArrayList<Integer> ownedHotelDaysAllocated = new ArrayList<Integer>();
    // These are the days I've actually request to be bought
    private ArrayList<Integer> requestedInboundFlights = new ArrayList<Integer>();
    private ArrayList<Integer> requestedOutboundFlights = new ArrayList<Integer>();
    private int allocatedHotelType;
    // The days may differ from their preferred days
    private int allocatedInDay;
    private int allocatedOutDay;

    public Client(int clientID, int allocatedHotelType, int allocatedInDay, int allocatedOutDay){
      this.clientID = clientID;
      this.allocatedHotelType = allocatedHotelType;
      this.allocatedInDay = allocatedInDay;
      this.allocatedOutDay = allocatedOutDay;
    }

    public boolean hasHotelFulfilled(){
      boolean isComplete = true;
      for(int k=allocatedInDay; k<allocatedOutDay; k++){
        if(!ownedHotelDaysAllocated.contains(k)){
          isComplete = false;
        }
      }
      return isComplete;
    }

    public boolean hasOwnedHotelAllocation(){
      if(ownedHotelDaysAllocated.size() > 0){
        return true;
      }else{
        return false;
      }
    }

    // AKA we won a hotel bid
    public void addOwnedHotelAllocation(int day){
      ownedHotelDaysAllocated.add(day);
      
      // Buy flights if day is at the edge of the allocated trip
      if(day == allocatedInDay){
        requestedInboundFlights.add(allocatedInDay);
        // TODO Call james's code to buy flight
      }else if(day == allocatedOutDay){
        requestedInboundFlights.add(allocatedOutDay);
        // TODO Call james's code to buy flight   
      }
    }

    public int getAllocatedHotelType(){
      return allocatedHotelType;
    }

    public int getAllocatedInDay(){
      return allocatedInDay;
    }

    public int getAllocatedOut(){
      return allocatedOutDay;
    }

    public boolean hotelWanted(int day, int hotelType){
      if(allocatedInDay <= day && day < allocatedOutDay 
          && allocatedHotelType == hotelType){
        return true;
      }
      return false;
    }

    // AKA we lost a hotel bid
    public void shortenTrip(int dayLost){
      if(ownedHotelDaysAllocated.size() != 0){

        // Check if left most hotel day
        if(dayLost == allocatedInDay){
          if(dayLost + 1 == allocatedOutDay){
            log.fine("Client "+clientID+" cannot fufilled trip due to hotels");
            return;
          } 
          if(ownedHotelDaysAllocated.contains(dayLost + 1)){
            // Remove old flight alllocation
            int oldAuction = agent.getAuctionFor(TACAgent.CAT_FLIGHT, TACAgent.TYPE_INFLIGHT, allocatedInDay);
            agent.setAllocation(inAuction, agent.getAllocation(oldAuction) - 1 );

            // We own the left most hotel for this trip, buy inbound flight
            allocatedInDay = allocatedInDay + 1;
            int inAuction = agent.getAuctionFor(TACAgent.CAT_FLIGHT, TACAgent.TYPE_INFLIGHT, allocatedInDay);
            agent.setAllocation(inAuction, agent.getAllocation(inAuction) + 1 );
            
            // TODO Call james's code to buy flight
            requestedInboundFlights.add(allocatedInDay);

            return;
          }
          // Check if there is a open auction availible
          int hotelAuction = agent.getAuctionFor(TACAgent.CAT_HOTEL, allocatedHotelType, dayLost + 1);
          if(!closedAuctions.contains(hotelAuction)){
            allocatedInDay = allocatedInDay + 1;
            return;
          }else{
            log.fine("ERROR - Should not reach. There is a closed auction between an allocated trip");
          }


        // Check if right most hotel day (no hotel needed on last day)
        }else if(dayLost == allocatedOutDay - 1){
          if(dayLost - 1 == allocatedInDay){
            log.fine("Client "+clientID+" cannot fufilled trip due to hotels");
            return;
          } 
          if(ownedHotelDaysAllocated.contains(dayLost - 1)){
            // Remove old flight alllocation
            int oldAuction = agent.getAuctionFor(TACAgent.CAT_FLIGHT, TACAgent.TYPE_OUTFLIGHT, allocatedOutDay);
            agent.setAllocation(inAuction, agent.getAllocation(oldAuction) - 1 );

            // We own the right most hotel for this trip, buy outbound flight and ammend allocation
            allocatedOutDay = allocatedOutDay - 1;
            int outAuction = agent.getAuctionFor(TACAgent.CAT_FLIGHT, TACAgent.TYPE_OUTFLIGHT, allocatedOutDay);
            agent.setAllocation(inAuction, agent.getAllocation(outAuction) + 1 );

            // TODO Call james's code to buy flight
            requestedOutboundFlights.add(allocatedOutDay);

            return;
          }
          int hotelAuction = agent.getAuctionFor(TACAgent.CAT_HOTEL, allocatedHotelType, dayLost - 1);
          if(!closedAuctions.contains(hotelAuction)){
            // Set new start day
            allocatedInDay = dayLost + 1;
            return
          }else{
            log.fine("ERROR - Should not reach. There is a closed auction between an allocated trip");
          }


        // Check if in the middle of an allocated trip
        }else if(allocatedInDay < dayLost && dayLost < allocatedOutDay -1){
          // Calculate best remainder trip, we can assume that everything between allocatedInDay
          // and allocatedOutDay except dayLost is a open auction or we own the hotel room. 
          int leftDuration = dayLost - allocatedInDay;
          int rightDuration = allocatedOutDay - 1 - dayLost;
          boolean leftIsBest;

          if(leftDuration > rightDuration){
            leftIsBest = true;
          }else if(leftDuration < rightDuration) {
            leftIsBest = false;
          }else if(leftDuration == rightDuration){
            if(requestedInboundFlights.contains(allocatedInDay)){
              // Bias towards side that has a flight purchased in even duration
              leftIsBest = true;
            }else if(requestedOutboundFlights.contains(allocatedOutDay)){
              leftIsBest = false;
            }else if(dayLost == 1){
              // In this scenerio, buying day 0 is likely cheaper than buying day 2
              leftIsBest = true;
            }else if(dayLost == 2){
              leftIsBest = false;
            }
          }

          if(leftIsBest){
            // Remove uneeded hotel allocations
            for(int d = dayLost + 1; d < allocatedOutDay; d++){
              int hotelAuction = agent.getAuctionFor(TACAgent.CAT_HOTEL, allocatedHotelType, d);
              agent.setAllocation(hotelAuction, agent.getAllocation(hotelAuction) -1);
            }
            allocatedOutDay = dayLost;
          }else{
            for(int d = allocatedInDay; d < dayLost; d++){
              int hotelAuction = agent.getAuctionFor(TACAgent.CAT_HOTEL, allocatedHotelType, d);
              agent.setAllocation(hotelAuction, agent.getAllocation(hotelAuction) -1);
            }
            allocatedInDay = dayLost + 1;
          }

          if(hasHotelFulfilled){
            // If this completes the trip, then we need to buy the missing 
            if(!requestedInboundFlights.contains(allocatedInDay)){

              // TODO Call james's code to buy flight
              requestedInboundFlights.add(allocatedInDay);
            }
            if(!requestedOutboundFlights.contains(allocatedOutDay)){

              // TODO Call james's code to buy flight
              requestedOutboundFlights.add(allocatedInDay);
            }
          }
        }
      }else{
        // Try to switch hotels
      }
    }
  }
} // DummyAgent
