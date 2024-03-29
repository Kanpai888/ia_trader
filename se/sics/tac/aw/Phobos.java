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

import java.util.ArrayList;
import java.util.ListIterator;
import java.util.logging.*;
import java.util.Comparator;
import java.util.Collections;
import java.util.Random;
import java.util.Arrays;

public class Phobos extends AgentImpl {
	
	//random number generator
	private Random random = new Random();
	
	private static final Logger log =
			Logger.getLogger(Phobos.class.getName());

	private static final boolean DEBUG = false;

	private float[] prices;
	private float[] previousPrices;
	private float[] currentFlightPrices;
	private float[] auctionDelta;

	// Store hotel price estimates
	private float[] cheapHotelEstimates;
	private float[] expensiveHotelEstimates;

	private boolean isInitialised = false;

	private ArrayList<Client> clients;

	protected void init(ArgEnumerator args) {
		prices = new float[TACAgent.getAuctionNo()];
	}

	// New information about the quotes on the auction (quote.getAuction())
	// has arrived
	public void quoteUpdated(Quote quote) {
		int auction = quote.getAuction();
		int auctionCategory = TACAgent.getAuctionCategory(auction);

		if (auctionCategory == TACAgent.CAT_FLIGHT) {
			currentFlightPrices[auction] = quote.getAskPrice(); // Update currentFlightPrices[]

			// If trend is negative, price going down
			float trend = quote.getAskPrice() - previousPrices[auction];
			int flightsNeeded = agent.getAllocation(auction) - agent.getOwn(auction);

			if (trend > 0 && flightsNeeded > 0) { // Prices are going up, so buy!
				Bid b = new Bid(auction);
				b.addBidPoint(flightsNeeded, 1000);
				agent.submitBid(b);

				// Remove the flights from monitoring and allocate them
				assignAuctionItems(auction, flightsNeeded);
			}
		} else if (auctionCategory == TACAgent.CAT_ENTERTAINMENT && isInitialised) { //if type is entertainment and isInitialised is true
	  
			int owned = agent.getOwn(auction); //number of tickets of this type owned
			int alloc = agent.getAllocation(auction); //number of tickets of this type allocated
			
			//log.fine("Entertainment " + auction + ": alloc = " + alloc + ", owned = " + owned);
			
			Bid bid = new Bid(auction);
			//sell all unallocated tickets for 101
			//since if agents spend over 100 on a ticket
			//the bonus they get must be less than 100
			//and the amount we gain is greater than 100
			if (alloc < owned || alloc == 4) { //also enter here if all possible tickets are owned and allocated (4)
				
				if(alloc < owned) { //only if fewer tickets are allocated than owned
					
					if (TACAgent.getAuctionDay(auction) == 1) {
						bid.addBidPoint(alloc - owned, 95f); //sell first day auction for lower price
					} else {
						bid.addBidPoint(alloc - owned, 101f);
					}
				} 
				
				//for all allocated tickets sell at 200
				for (int ticketNo = 0; ticketNo < alloc; ticketNo++) {
					
					bid.addBidPoint(-1, 200f);
					
				}
			} else { //buy tickets here
				
				//get the dat and type of the auction
				int type, day;
				type = TACAgent.getAuctionType(auction);
				day = TACAgent.getAuctionDay(auction);
				
				//get the bonus for getting an extra ticket
				float bonus = getAdditionalBonusForGivenTicket(new ETicket(type, day));
				
				//limit bonus to 103
				if (bonus > 101) { bonus = 101; }
				
				if (bonus > 50) {
					//set the price to bid to a random number between bonus-50 and bonus-10
					float priceToPay = bonus - 50;
					priceToPay += random.nextInt(40);
					bid.addBidPoint(1, priceToPay);
				} else if (bonus > 20) {
					//set the price between bonus-20 and bonus-2
					float priceToPay = bonus - 20;
					priceToPay += random.nextInt(8);
					bid.addBidPoint(1, priceToPay);
				} else {
					//float priceToPay = 
					//bid.addBidPoint(1, random.nextInt(bonus) + 1f); //bid up to the price
				}
			}
			
			agent.submitBid(bid);
		}
		// previousPrices[auction] = quote.getAskPrice();
	}



	// New information about the quotes on all auctions for the auction
	// category has arrived (quotes for a specific type of auctions are
	// often requested at once).
	public void quoteUpdated(int auctionCategory) {

		//    log.fine("All quotes for " + TACAgent.auctionCategoryToString(auctionCategory) + " has been updated");

		// Only submit hotel bids if all hotel quotes have been updated and we have 
		// updated our estimates. This allows us to get the clients to 
		if (isInitialised && auctionCategory == TACAgent.CAT_HOTEL) {
			for (int auction = 0, n = TACAgent.getAuctionNo(); auction < n; auction++) {
				if (TACAgent.getAuctionCategory(auction) == TACAgent.CAT_HOTEL){
					Quote quote = agent.getQuote(auction);

					if (auctionDelta[auction] < quote.getAskPrice() - previousPrices[auction]) {
						auctionDelta[auction] = quote.getAskPrice() - previousPrices[auction];
					}

					previousPrices[auction] = quote.getAskPrice();

					prices[auction] = quote.getAskPrice() + auctionDelta[auction] + 5;

					// Update the estimates that are used in getUtility()
					float[] estimates;
					if (TACAgent.getAuctionType(auction) == TACAgent.TYPE_CHEAP_HOTEL) {
						estimates = cheapHotelEstimates;
					} else {
						estimates = expensiveHotelEstimates;
					}

					if (quote.isAuctionClosed()) {
						estimates[TACAgent.getAuctionDay(auction) - 1] = 9999;
					}else {
						estimates[TACAgent.getAuctionDay(auction) - 1] = prices[auction];
					}

				}
			} 
			log.fine("All hotel auction estimates updated");
		}
		
		//update entertainment details
		if (isInitialised && auctionCategory == TACAgent.CAT_ENTERTAINMENT) {
			updateAllEntertainmentBonuses();
		}

		// We only initialise the allocation table after we get the first set of flight prices
		if(agent.getGameTime() > 15000 && isInitialised == false && auctionCategory == TACAgent.CAT_FLIGHT){
			isInitialised = true;
			calculateAllocation();
			sendInitialBids();
		}

	}

	// There are TACAgent have received an answer on a bid query/submission
	// (new information about the bid is available)
	public void bidUpdated(Bid bid) {
		//    log.fine("Bid Updated: id=" + bid.getID() + " auction=" + bid.getAuction() + " state=" + bid.getProcessingStateAsString());
		//    log.fine("       Hash: " + bid.getBidHash());
	}

	// The bid has been rejected (reason is bid.getRejectReason())
	public void bidRejected(Bid bid) {
		//    log.warning("Bid Rejected: " + bid.getID());
		//    log.warning("      Reason: " + bid.getRejectReason() + " (" + bid.getRejectReasonAsString() + ')');
		int auction = bid.getAuction();
		log.warning("-----------------------------------------------------");
		log.warning("Bid Rejected: " + bid.getID());
		log.warning("      Reason: " + bid.getRejectReason() + " (" + bid.getRejectReasonAsString() + ')');
		log.warning("        Type: " + TACAgent.auctionCategoryToString(TACAgent.getAuctionCategory(bid.getAuction()))  );
		log.warning("Asking Price: " + agent.getQuote(auction).getAskPrice());
		log.warning("   Bid Price: " + bid.getBidString());
		log.warning(" Gd Estimate: " + expensiveHotelEstimates[TACAgent.getAuctionDay(auction) - 1]);
		log.warning(" Bd Estimate: " + cheapHotelEstimates[TACAgent.getAuctionDay(auction) - 1]);

	}

	// The bid contained errors (error represent error status - commandStatus)
	public void bidError(Bid bid, int status) {
		//    log.warning("Bid Error in auction " + bid.getAuction() + ": " + status + " (" + agent.commandStatusToString(status) + ')');
		int auction = bid.getAuction();
		log.warning("-----------------------------------------------------");
		log.warning("   Bid Error: " + bid.getID());
		log.warning("      Reason: " + bid.getRejectReason() + " (" + bid.getRejectReasonAsString() + ')');
		log.warning("        Type: " + TACAgent.auctionCategoryToString(TACAgent.getAuctionCategory(bid.getAuction()))  );
		log.warning("Asking Price: " + agent.getQuote(auction).getAskPrice());
		log.warning("   Bid Price: " + bid.getBidString());
		log.warning(" Gd Estimate: " + expensiveHotelEstimates[TACAgent.getAuctionDay(auction) - 1]);
		log.warning(" Bd Estimate: " + cheapHotelEstimates[TACAgent.getAuctionDay(auction) - 1]);
	}

	// A TAC game has started, and all information about the
	// game is available (preferences etc).
	public void gameStarted() {
		log.fine("Game " + agent.getGameID() + " started!");
		isInitialised = false;
		cheapHotelEstimates = new float[]{0,0,0,0};
		expensiveHotelEstimates = new float[]{0,0,0,0};
		currentFlightPrices = new float[TACAgent.getAuctionNo()]; // Reset flight prices array
		previousPrices = new float[TACAgent.getAuctionNo()]; // Reset the previous prices array
		auctionDelta  = new float[TACAgent.getAuctionNo()]; // Reset the auction delta

		for (int i = 0; i < auctionDelta.length; ++i) {
			auctionDelta[i] = 50;
		}

		clients = new ArrayList<Client>(); //
	}

	// The current game has ended
	public void gameStopped() {
		log.fine("Game Stopped!");
	}

	// The auction with id "auction" has closed
	public void auctionClosed(int auction) {
		// When a hotel auction closes, change the estimated price to 9999 to
		// prevent other clients using a trip with it
		if (TACAgent.getAuctionCategory(auction) == TACAgent.CAT_HOTEL) {
			log.fine("**** Hotel closed called for auction: "+auction);
			int day = TACAgent.getAuctionDay(auction) - 1; // Need to subtract 1 as array starts at index 0
			if (TACAgent.getAuctionType(auction) == TACAgent.TYPE_GOOD_HOTEL) {
				expensiveHotelEstimates[day] = 9999;
			} else {
				cheapHotelEstimates[day] = 9999;
			}

			// Assign the hotels to clients that want them
			assignAuctionItems(auction, agent.getOwn(auction));

			evaluateClientsFufillness();

			for (Client c : clients) {
				c.refreshSelectedTrip(false);
			}
			//for entertainment
		} else if (TACAgent.getAuctionCategory(auction) == TACAgent.CAT_ENTERTAINMENT) {
			//update all entertainment bonuses
			updateAllEntertainmentBonuses();
		}

		log.fine("*** Auction " + auction + " closed!");

		for (int auctionNumber = 0, n = TACAgent.getAuctionNo(); auctionNumber < n; auctionNumber++) {
			if (TACAgent.getAuctionCategory(auctionNumber) == TACAgent.CAT_HOTEL){
				Quote quote = agent.getQuote(auctionNumber);
				int alloc = agent.getAllocation(auctionNumber); // Allocation is number of items wanted from this auction

				Bid hotelBid = new Bid(auctionNumber);
				if(alloc > 0){
					hotelBid.addBidPoint(alloc, prices[auctionNumber]);
				}

				// Compulsary bid at lowest value
				int unwanted = quote.getHQW() - alloc;
				if(unwanted > 0){
					hotelBid.addBidPoint(unwanted, quote.getAskPrice() + 1);
				}
				if(alloc > 0 && !quote.isAuctionClosed()){
					// Only bid, if you have something to bid for
					agent.submitBid(hotelBid);
				}
			}
		} 

		log.fine("*** New bids submitted!");
	}

	/**
	 * The eticket list in clients
	 */
	private void updateAllEntertainmentBonuses() {
		log.fine("Updating entertainment allocation information");
		
		//reset all arrays
		for (Client client : clients) {
			client.updateETicketArrayLength();
		}
		
		//create an arraylist of all owned tickets
		ArrayList<ETicket> allOwnedTickets = new ArrayList<ETicket>();
		for (int type = TACAgent.TYPE_ALLIGATOR_WRESTLING; type <= TACAgent.TYPE_MUSEUM; type++) {
			
			for (int day = 1; day < 5; day++) {
				int auction = TACAgent.getAuctionFor(TACAgent.CAT_ENTERTAINMENT, type, day);
				int owned = agent.getOwn(auction);
				
				for (int i = 0; i < owned; i++) {
					allOwnedTickets.add(new ETicket(type, day));
				}
			}
		}
		
		//go through all owned tickets
		ArrayList<Client> tempClients = new ArrayList<Client>(clients);
		for (ETicket ticket : allOwnedTickets) {
			//remove allocations from allocation table
			agent.setAllocation(ticket.getAuctionNumber(), 0);
			
			//sort tempClient according to ticket type
			switch (ticket.getType()) {
				case TACAgent.TYPE_ALLIGATOR_WRESTLING:
					Collections.sort(tempClients, new ClientEntertainmentOneComparator());
					break;
					
				case TACAgent.TYPE_AMUSEMENT:
					Collections.sort(tempClients, new ClientEntertainmentTwoComparator());
					break;
					
				default: //TYPE_MUSEUM
					Collections.sort(tempClients, new ClientEntertainmentThreeComparator());
					break;
			}
			
			//assign the ticket to a client
			boolean isAssigned = false;
			int currentClient = 7;
			ETicket tempTicket = ticket;
			while (!isAssigned && currentClient >= 0) {
				Client currentClientObject = tempClients.get(currentClient);
				
				//check if this ticket will provide any bonus to the client
				if (currentClientObject.getSelectedTrip().getInFlight() <= tempTicket.getDay() //after or on in day
						&& currentClientObject.getSelectedTrip().getOutFlight() > tempTicket.getDay())  //before out day
				{
					//for each available space in the eTickets list
					for (int i = 0; i < currentClientObject.getETicketList().length; i++) {
						
						if (currentClientObject.getETicketList()[i] == null) {
							clients.get(currentClientObject.getClientNumber()).setETicketInArray(tempTicket, i);
							tempTicket = null;
							isAssigned = true;
							break;
						} else if (currentClientObject.getETicketList()[i].getClientBonus(currentClientObject) < tempTicket.getClientBonus(currentClientObject)) {
							ETicket temp2 = currentClientObject.getETicketList()[i];
							clients.get(currentClientObject.getClientNumber()).setETicketInArray(tempTicket, i);
							tempTicket = temp2;
							currentClient = 8;
						}
					}
				}
				if (!isAssigned) { currentClient--; }
			}
		}
		
		//go through all clients and update allocation table
		for (Client c : clients) {
			for (ETicket t : c.getETicketList()) {
				if (t != null) {
					agent.setAllocation(t.getAuctionNumber(), agent.getAllocation(t.getAuctionNumber()) + 1);
				}
			}
		}
		log.fine("Finished updating entertainment allocation information");
	}

	/**
	 * Gets the optimal entertainment bonus for a trip
	 * @param newTicket the new ticket to consider
	 * @return int The optimal bonus
	 */
	private int getAdditionalBonusForGivenTicket(ETicket newTicket) {
		int bonusDifference = 0;
		
		ArrayList<Client> tempClients = new ArrayList<Client>(clients);
		
		switch (newTicket.getType()) {
			case TACAgent.TYPE_ALLIGATOR_WRESTLING:
				Collections.sort(tempClients, new ClientEntertainmentOneComparator());
				break;
				
			case TACAgent.TYPE_AMUSEMENT:
				Collections.sort(tempClients, new ClientEntertainmentTwoComparator());
				break;
				
			default: //TYPE_MUSEUM
				Collections.sort(tempClients, new ClientEntertainmentThreeComparator());
				break;
		}
		
		//assign the ticket to a client
		boolean isAssigned = false;
		int currentClient = 7;
		ETicket tempTicket = newTicket;
		while (!isAssigned && currentClient >= 0) {
			Client currentClientObject = tempClients.get(currentClient);
			
			//check if this ticket will provide any bonus to the client
			if (currentClientObject.getSelectedTrip().getInFlight() <= tempTicket.getDay() //after or on in day
					&& currentClientObject.getSelectedTrip().getOutFlight() > tempTicket.getDay())  //before out day
			{
				//for each available space in the eTickets list
				for (int i = 0; i < currentClientObject.getETicketList().length; i++) {
					
					if (currentClientObject.getETicketList()[i] == null) {
						clients.get(currentClientObject.getClientNumber()).setETicketInArray(tempTicket, i);
						bonusDifference += tempTicket.getClientBonus(currentClientObject);
						tempTicket = null;
						isAssigned = true;
						break;
					} else if (currentClientObject.getETicketList()[i].getClientBonus(currentClientObject) < tempTicket.getClientBonus(currentClientObject)) {
						bonusDifference += tempTicket.getClientBonus(currentClientObject);
						ETicket temp2 = currentClientObject.getETicketList()[i];
						clients.get(currentClientObject.getClientNumber()).setETicketInArray(tempTicket, i);
						tempTicket = temp2;
						currentClient = 8;
						bonusDifference -= tempTicket.getClientBonus(currentClientObject);
					}
				}
			}
			if (!isAssigned) { currentClient--; }
		}
		
		return bonusDifference;
	}

	
	/**
	 * Assigns auction costs to clients. Can cause clients to change trips 
	 * @param auction
	 * @param cost
	 * @param number
	 */
	private void assignAuctionItems(int auction, int number) {
		for(Client client:clients){
			if(client.resourceWanted(auction) && number > 0){
				client.assignAuctionItem(auction);
				number--;
			}
		}
		//update all entertainment bonuses
		updateAllEntertainmentBonuses();
	}

	/**
	 * Causes all clients to evaluate if they have a full trip.
	 */
	private void evaluateClientsFufillness(){
		ArrayList<Integer> allResources = new ArrayList<Integer>();
		// Create an arrayList of all resources we own,
		// e.g if we have 3 of auction num 8, then we put in the arrayList
		// {8,8,8}. This is used to substract from.
		int n = TACAgent.getAuctionNo();
		//	  log.fine("+++Evaluate Client Fufillness+++");
		for (int i = 0 ; i < n; i++) {
			for(int num = 0; num < agent.getOwn(i); num++){
				allResources.add(i);
			}
		}
		//	  log.fine(allResources.toString());

		for(Client client:clients){
			client.evaluateFufillness(allResources);
			//		  log.fine(allResources.toString());
		}
	}

	// Sends initial bids
	private void sendInitialBids() {
		for (int i = 0, n = TACAgent.getAuctionNo(); i < n; i++) {
			int alloc = agent.getAllocation(i) - agent.getOwn(i);
			float price = -1f;
			switch (TACAgent.getAuctionCategory(i)) {
			case TACAgent.CAT_HOTEL:
				if (alloc > 0) {
					price = 200;
					prices[i] = 200f;
					previousPrices[i] = 200f;
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
			} else if (TACAgent.getAuctionCategory(i) == TACAgent.CAT_HOTEL) {
				Bid b = new Bid(i);
				b.addBidPoint(5, 1);
				agent.submitBid(b);
			}

		}
	}

	// Sets the 'allocation' to the required flights, hotels, and entertainment.
	// An allocation is a item that we need to provide to the client.
	private void calculateAllocation() {
		// Loop through for each of the 8 clients
		log.fine("Initial Allocations");
		for (int i = 0; i < 8; i++) {
			//work out simple entertainment bonus for initialisation
			int inFlight = agent.getClientPreference(i, TACAgent.ARRIVAL);
			int outFlight = agent.getClientPreference(i, TACAgent.DEPARTURE);
			int eType = -1;
			int eBonus = 0;
			int currentDay = inFlight;
			while((eType = nextEntType(i, eType)) > 0 && currentDay < outFlight) {
				//        log.finer("Adding entertainment " + eType + " on " + auction);
				eBonus += agent.getClientPreference(i, eType);
				currentDay++;
			}

			// Add the client to the ArrayList. Allocations will be dealt with later
			clients.add(new Client(i, eBonus));
			int in = clients.get(i).getInFlight();
			int out = clients.get(i).getOutFlight();
			int hotel = clients.get(i).getSelectedTrip().getHotelType();
			
			log.fine("Client " + i +" , in-"+in +" , out-"+out+" hotel-" +hotel );
		}
		updateAllEntertainmentBonuses();
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
		private Trip selectedTrip;
		private boolean tripFufilled;
		private int currentEntertainmentBonus;
		private ArrayList<Integer> assignedItems;
		
		//Assigned entertainment tickets
		private ETicket[] etickets;

		public Client(int clientNumber, int initialEntertainmentBonus) {
			// Initialise vars
			tripFufilled = false;
			possibleTrips = new ArrayList<Trip>();
			assignedItems = new ArrayList<Integer>();
			currentEntertainmentBonus = initialEntertainmentBonus;

			this.clientNumber = clientNumber;
			// Use client number to get and store preferences
			this.preferredInFlight = agent.getClientPreference(clientNumber, TACAgent.ARRIVAL);
			this.preferredOutFlight = agent.getClientPreference(clientNumber, TACAgent.DEPARTURE);
			this.hotelBonus = agent.getClientPreference(clientNumber, TACAgent.HOTEL_VALUE);

			buildClientTrips(); // Create permutations for the possible client trips
			this.selectedTrip = getOptimalTrip(); // This is the trip we will try to build
			updateAllocationTable();
		}

		/**
		 * Changes the current selected trip to the most optimal one.
		 * Can only shorten trip if it has not already been fulfilled
		 * @param submitClientBid do we submit the bids for any new trip selected?
		 */
		public void refreshSelectedTrip(boolean submitClientBid){
			if(!tripFufilled){
				clearAllocationTable();
				Trip t = selectedTrip;
				this.selectedTrip = getOptimalTrip();

				releaseUnusedItems();

				// See if there are any unassigned items that we can use
				for (int auction = 0, n = TACAgent.getAuctionNo(); auction < n; auction++) {
					int unusedOwned = agent.getOwn(auction) - agent.getAllocation(auction);
					int catergory = TACAgent.getAuctionCategory(auction);
					if (catergory == TACAgent.CAT_HOTEL || catergory == TACAgent.CAT_FLIGHT){
						if(selectedTrip.getAuctions().contains(auction)
								&& !assignedItems.contains(auction)
								&& unusedOwned > 0){

							assignedItems.add(auction);
						}
					}
				}
				updateAllocationTable();
				evaluateClientsFufillness();

				if (t != selectedTrip) {
					log.fine("+++ Client " + clientNumber + " has switched trips");
					log.fine("Previous: " + t.getInFlight() + " to " + t.getOutFlight() + ", type " + t.getHotelType());
					log.fine("     New: " + selectedTrip.getInFlight() + " to " + selectedTrip.getOutFlight() + ", type " + selectedTrip.getHotelType());
					if(submitClientBid){
						sendUpdatedBids();
					} 
				}
			}
		}

		public void sendUpdatedBids() {
			for (Integer auction : selectedTrip.getAuctions()) {
				if (TACAgent.getAuctionCategory(auction) == TACAgent.CAT_HOTEL){

					int alloc = agent.getAllocation(auction); // Allocation is number of items wanted from this auction

					Bid hotelBid = new Bid(auction);
					if(alloc > 0){
						hotelBid.addBidPoint(alloc, prices[auction]);
					}

					// Compulsary bid at lowest value
					Quote quote =  agent.getQuote(auction);
					int unwanted = quote.getHQW() - alloc;
					if(unwanted > 0){
						hotelBid.addBidPoint(unwanted, quote.getAskPrice() + 1);
					}
					if(alloc > 0 && !quote.isAuctionClosed()){
						// Only bid, if you have something to bid for
						agent.submitBid(hotelBid);
					}

				}
			}
		}

		/**
		 * Used to check if a given resource is wanted by this client
		 * @param auction
		 * @return
		 */
		public boolean resourceWanted(int auction){
			if(!tripFufilled && selectedTrip.getAuctions().contains(auction)
					&& !assignedItems.contains(auction)){
				return true;
			}
			return false;
		}

		/**
		 * An auction has been won for an item this client wanted, so add it to their list
		 * @param auction
		 * @param price
		 */
		public void assignAuctionItem(int auction) {
			assignedItems.add(auction);
			log.fine("*** Client " + clientNumber + " has been allocated Auction ID " + auction);
		}

		/**
		 * Tests if this clients trip can be fulfilled with the available resources,
		 * passed in via the param. Will remove ints from arraylist
		 * @param resources auction IDs for client to pick from
		 */
		public void evaluateFufillness(ArrayList<Integer> resources){
			boolean fufilled = true;

			for(int wanted: selectedTrip.getAuctions()){
				if(resources.contains(new Integer(wanted))){
					resources.remove(new Integer(wanted));
				}else{
					fufilled = false;

				}
			}
			if(!this.tripFufilled && fufilled){
				// Only wanted to know the first time its been set as fufilled
				log.fine("++Client " + clientNumber + " fufilled");
				releaseUnusedItems();
			}
			this.tripFufilled = fufilled;
		}

		private void releaseUnusedItems(){
			ListIterator<Integer> iter = assignedItems.listIterator();
			// Removing undeeded items and giving it to another client
			while(iter.hasNext()){
				int auction = new Integer(iter.next());
				if( !selectedTrip.auctions.contains(auction) ){
					assignAuctionItems(auction, 1);
					iter.remove();
				}
			}
		}

		/**
		 * Create every possible permutation of client trip and stores it
		 */
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

		/**
		 * Removes allocations that relate to this client
		 */
		private void clearAllocationTable(){
			int alloc;
			for(int auction: selectedTrip.getAuctions()){
				alloc = agent.getAllocation(auction);
				agent.setAllocation(auction, alloc - 1);
			}
		}

		/**
		 * Adds allocations that relate to this client
		 */
		private void updateAllocationTable(){
			int alloc;
			for(int auction: selectedTrip.getAuctions()){
				alloc = agent.getAllocation(auction);
				agent.setAllocation(auction, alloc + 1);
			}
		}

		/**
		 * Return the trip with the highest utility
		 * @return Optimal utility trip for client
		 */
		private Trip getOptimalTrip() {
			Trip currentHighest = possibleTrips.get(0);
			float currentHighestUtility = 0;
			for (Trip t : possibleTrips) {
				float tempUtil = t.getUtility();
				if (tempUtil > currentHighestUtility) {
					currentHighest = t;
					currentHighestUtility = tempUtil;
				}
			}
			return currentHighest;
		}

		/**
		 * Return optimal trip that doesn't use ignoreAuction
		 * @param ignoreAuction
		 * @return Best trip without a given auction
		 */
		private Trip getOptimalTrip(int ignoreAuction) {
			Trip currentHighest = null;

			// Get a trip without that day for the initial comparison
			for (Trip t : possibleTrips) {
				if (!t.containsHotel(ignoreAuction)) {
					currentHighest = t;
				}
			}

			if (currentHighest == null) {
				// There are no trips available without this day. This should never happen
				log.warning("*** Unable to find a trip for client " + clientNumber + " without day " + ignoreAuction);
				log.warning("*** Clients preferences were inFlight " + preferredInFlight + " and outFlight " + preferredOutFlight);
			} else {
				for (Trip t : possibleTrips) {
					if (!t.containsHotel(ignoreAuction) && t.getUtility() > currentHighest.getUtility()) {
						currentHighest = t;
					}
				}
			}
			return currentHighest;
		}
		
		//set the etickets array to be of the required size
		public void updateETicketArrayLength() {
			int dur = selectedTrip.getOutFlight() - selectedTrip.getInFlight();
			
			if (dur > 3) { dur = 3; }
			
			etickets = new ETicket[dur];
		}
		
		//set a specific slot to a specific ticket
		public void setETicketInArray(ETicket ticket, int slot) {
			etickets[slot] = ticket;
		}
		
		public int getClientNumber() { return clientNumber; }
		public int getInFlight() { return preferredInFlight; }
		public int getOutFlight() { return preferredOutFlight; }
		public int getHotelBonus() { return hotelBonus; }
		public ArrayList<Integer> getAssignedItems() { return assignedItems; }
		public Trip getSelectedTrip() { return selectedTrip; }
		public ETicket[] getETicketList() { return etickets; }

		//currentEntertainmentBonus methods
		public int getCurrentEntertainmentBonus() { return currentEntertainmentBonus; }
		public void setCurrentEntertainmentBonus(int bonus) { currentEntertainmentBonus = bonus; }
		public void increaseCurrentEntertainmentBonus(int increase) { currentEntertainmentBonus += increase; }

	} // Client

	public class ClientEntertainmentOneComparator implements Comparator<Client> {

		@Override
		public int compare(Client a, Client b) {

			return ((Integer) agent.getClientPreference(a.getClientNumber(), 
					TACAgent.E1)).compareTo(agent.getClientPreference(b.getClientNumber()
							, TACAgent.E1));

		}
	} //ClientEntertainmentOneComparator

	public class ClientEntertainmentTwoComparator implements Comparator<Client> {

		@Override
		public int compare(Client a, Client b) {

			return ((Integer) agent.getClientPreference(a.getClientNumber(), 
					TACAgent.E2)).compareTo(agent.getClientPreference(b.getClientNumber()
							, TACAgent.E2));

		}
	} //ClientEntertainmentTwoComparator

	public class ClientEntertainmentThreeComparator implements Comparator<Client> {

		@Override
		public int compare(Client a, Client b) {

			return ((Integer) agent.getClientPreference(a.getClientNumber(), 
					TACAgent.E3)).compareTo(agent.getClientPreference(b.getClientNumber()
							, TACAgent.E3));

		}
	} //ClientEntertainmentThreeComparator

	public class Trip {

		private Client client;
		private int inFlight;
		private int outFlight;
		private int hotelType;
		private ArrayList<Integer> auctions; // A list of the auctions used in this trip
		private float[] estimatedHotelPrices;
		private boolean firstRun = true;

		public Trip(Client c, int inFlight, int outFlight, int hotelType) {
			auctions = new ArrayList<Integer>();

			this.client = c;
			this.inFlight = inFlight;
			this.outFlight = outFlight;
			this.hotelType = hotelType;

			if (hotelType == TACAgent.TYPE_GOOD_HOTEL) {
				estimatedHotelPrices = expensiveHotelEstimates;
			} else {
				estimatedHotelPrices = cheapHotelEstimates;
			}

			// Add all the auction IDs used by trip to auctions ArrayList
			auctions.add(TACAgent.getAuctionFor(TACAgent.CAT_FLIGHT, TACAgent.TYPE_INFLIGHT, inFlight)); // InFlight
			auctions.add(TACAgent.getAuctionFor(TACAgent.CAT_FLIGHT, TACAgent.TYPE_OUTFLIGHT, outFlight)); //OutFlight
			for (int i = inFlight; i < outFlight; ++i) {
				auctions.add(TACAgent.getAuctionFor(TACAgent.CAT_HOTEL, hotelType, i));
			}
		}

		private float calculateUtility() {
			float hotelCost = 0;
			// Get the clients preferred dates and hotel bonus
			int preferredInFlight = client.getInFlight();
			int preferredOutFlight = client.getOutFlight();
			float hotelBonus = client.getHotelBonus();

			// Also get the items owned by the client, and the prices paid
			ArrayList<Integer> assignedItems = client.getAssignedItems();

			// Calculate the penalty when using these flight dates
			float travelPenalty = (inFlight - preferredInFlight) * 100;
			travelPenalty += (preferredOutFlight - outFlight) * 100;

			// Add up the expected cost of flights. If flights already owned by client
			// use the price paid
			float flightCost = 0;
			int auction = TACAgent.getAuctionFor(TACAgent.CAT_FLIGHT, TACAgent.TYPE_INFLIGHT, inFlight);
			if( !assignedItems.contains(auction) && agent.getAllocation(auction) >= agent.getOwn(auction)){
				// Only apply inbound flight cost, if we need to buy a flight
				flightCost += currentFlightPrices[auction];
			}
			// If we own any unused flights, then consider it a cost
			if( !assignedItems.contains(auction) ){
				for(int ownedItem: assignedItems){
					if(TACAgent.getAuctionCategory(ownedItem) == TACAgent.CAT_FLIGHT
							&& TACAgent.getAuctionType(ownedItem) == TACAgent.TYPE_INFLIGHT){
						flightCost += 380;
					}
				}
			}

			auction = TACAgent.getAuctionFor(TACAgent.CAT_FLIGHT, TACAgent.TYPE_OUTFLIGHT, outFlight);
			if( !assignedItems.contains(auction) && agent.getAllocation(auction) >= agent.getOwn(auction)){
				// Only apply outbound flight cost, if we need to buy a flight
				flightCost += currentFlightPrices[auction];
			}
			if( !assignedItems.contains(auction) ){
				for(int ownedItem: assignedItems){
					if(TACAgent.getAuctionCategory(ownedItem) == TACAgent.CAT_FLIGHT
							&& TACAgent.getAuctionType(ownedItem) == TACAgent.TYPE_OUTFLIGHT){
						flightCost += 380;
					}
				}
			}

			// Add up the expected cost of these hotel rooms
			// If the client owns that hotel room, use the price paid. Otherwise, use
			// the estimated price
			for (int i = inFlight; i < outFlight; ++i) {
				// Need to get auction number to check if client owns hotel
				auction = TACAgent.getAuctionFor(TACAgent.CAT_HOTEL, hotelType, i);
				if( !assignedItems.contains(auction) && agent.getAllocation(auction) >= agent.getOwn(auction)){
					// Only apply hotel cost, if we need to buy a hotel
					hotelCost += estimatedHotelPrices[i - 1];
				}
			}

			// Added cost of hotels that it's already bidding on and might win
			// Makes it less likely to want to change
			if(!firstRun){
				for (int existingAuction:client.getSelectedTrip().getAuctions()) {
					if (TACAgent.getAuctionCategory(existingAuction) == TACAgent.CAT_HOTEL){
						Quote quote = agent.getQuote(existingAuction);
						// Check if we have hypothetically won something already that we don't use
						if (!quote.isAuctionClosed() 
								&& quote.getHQW() > agent.getAllocation(existingAuction) + 1 
								&& !this.auctions.contains(existingAuction)){

							if (TACAgent.getAuctionType(existingAuction) == TACAgent.TYPE_GOOD_HOTEL){
								hotelCost += expensiveHotelEstimates[TACAgent.getAuctionDay(existingAuction) - 1];
							}else {
								hotelCost += cheapHotelEstimates[TACAgent.getAuctionDay(existingAuction) - 1];
							}
						} else {
							// If the auction is closed, then we add a fixed costs
							if(!this.auctions.contains(existingAuction)){
								hotelCost += 150;								
							}
						}
					}
				}
			}

			// Negate the hotel bonus if using the cheap hotel
			if (hotelType != TACAgent.TYPE_GOOD_HOTEL) {
				hotelBonus = 0;
			}

			// Ideally we'd have something about the entertainment here, but I have
			// no idea what to do with that. Maybe Ryan can add something?
			/*int eBonus = 0;
			if (firstRun) {
				firstRun = false;
			    eBonus = this.client.getCurrentEntertainmentBonus();
			} else {
			    //eBonus = getOptimalEntertainmentBonusForTrip(this.client.getClientNumber(), this);
			}*/
			
			int clientNum = client.getClientNumber();
			int[] preferences = new int[3];
			
			int maxIt = outFlight - inFlight;
			if (maxIt > 3) { maxIt = 3; }
			
			preferences[0] = agent.getClientPreference(clientNum, TACAgent.E1);
			preferences[1] = agent.getClientPreference(clientNum, TACAgent.E2);
			preferences[2] = agent.getClientPreference(clientNum, TACAgent.E3);
			
			Arrays.sort(preferences);
			int eBonus = 0;
			for (int i = maxIt - 1; i >= 0; i--) {
				eBonus += preferences[i];
			}
			
			eBonus /= 2;
			
			// Calculate the overall utility of this trip
			return 1000 - travelPenalty - flightCost - hotelCost + hotelBonus + eBonus;
			//return 1000 - travelPenalty - flightCost - hotelCost + hotelBonus;
		}

		// Method to return whether a hotel is used in this trip or not. Will be used
		// to delete trip if auction closes for a hotel this trip needed, and none are
		// owned by the client
		public boolean containsHotel(int auctionNumber) { return auctions.contains(auctionNumber); }
		public boolean containsDay(int day) {
			int auction1 = TACAgent.getAuctionFor(TACAgent.CAT_HOTEL, TACAgent.TYPE_GOOD_HOTEL, day);
			int auction2 = TACAgent.getAuctionFor(TACAgent.CAT_HOTEL, TACAgent.TYPE_CHEAP_HOTEL, day);
			return auctions.contains(auction1) || auctions.contains(auction2);
		}

		// Other getters
		public float getUtility() { return calculateUtility(); }
		public ArrayList<Integer> getAuctions() { return auctions; }
		public int getInFlight() { return inFlight; }
		public int getOutFlight() { return outFlight; }
		public int getHotelType() { return hotelType; }
		public String toString() {
			return "Trip for Client " + client.getClientNumber() + " with inFlight: " + inFlight + 
					", outFlight: " + outFlight + ", hotelType: " + hotelType + ", utility: " + getUtility();
		}

	} // Trip
	
	public class ETicket {
		
		private int type;
		private int day;
		
		public ETicket(int eType, int eDay) {
			this.type = eType;
			this.day = eDay;
		}
		
		public int getType() { return type; }
		public int getDay() { return day; }
		
		public int getClientPreferenceType() {
			
			switch (type) {
				
				case TACAgent.TYPE_ALLIGATOR_WRESTLING:
					return TACAgent.E1;
				
				case TACAgent.TYPE_AMUSEMENT:
					return TACAgent.E2;
					
				case TACAgent.TYPE_MUSEUM:
					return TACAgent.E3;
					
				default:
					return -1;
			}
		}
		
		//get auction number
		public int getAuctionNumber() {
			return TACAgent.getAuctionFor( agent.CAT_ENTERTAINMENT, type, day);
		}
		
		//get the bonus for a specific client
		public int getClientBonus(Client client) {
			int clientNo = client.getClientNumber();
			return agent.getClientPreference(clientNo, getClientPreferenceType());
		}
		
	}//ETicket

} // DummyAgent
