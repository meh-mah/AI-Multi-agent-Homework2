package Agents;

import jade.core.Agent;
import jade.core.behaviours.Behaviour;
import jade.core.behaviours.FSMBehaviour;
import jade.domain.DFService;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.ServiceDescription;
import jade.domain.FIPAException;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import jade.lang.acl.UnreadableException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.logging.Level;
import java.util.logging.Logger;
import model.Artifact;

/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
/**
 *
 * @author M&M
 */
public class CuratorAgent extends Agent {
    private List<Artifact> arts = new ArrayList<>();

    @Override
    protected void setup() {

        System.out.println("Hello! Curator " + getAID().getName() + " is ready...");

        //Register service in DF
        DFAgentDescription dfd = new DFAgentDescription();
        dfd.setName(getAID());
        ServiceDescription sd = new ServiceDescription();
        sd.setType("curator");
        sd.setName("bider_curator");
        dfd.addServices(sd);
        try {
            DFService.register(this, dfd);
        } catch (FIPAException ex) {
            Logger.getLogger(CuratorAgent.class.getName()).log(Level.SEVERE, null, ex);
        }


        addBehaviour(new DutchAuctionBiders());
    }

    @Override
    protected void takeDown() {
        super.takeDown();
    }

    class DutchAuctionBiders extends FSMBehaviour {
        private MessageTemplate msgTemp;
        private Random rand;
        private double price;
        private double maxValue;
        private double minValue;
        private double trueValue;
        private Artifact art;
        private double noOfBiders;
        private double priceToBid;
        
        // Define transitions
        private final int noBids = 0;
        private final int sendProposal = 1;
        private final int highPrice = 2;

        @Override
        public void onStart() {
            msgTemp = MessageTemplate.MatchPerformative(ACLMessage.INFORM);
            rand = new Random(System.nanoTime());

            // Register states
            registerFirstState(new WaitForAuction(myAgent), "wait-auction");
            registerState(new ReceiveCfp(myAgent), "wait-cfp");
            registerState(new ReceiveReply(myAgent), "wait-proposal-reply");
            registerLastState(new EndOfAuction(myAgent), "end-auction");
            registerLastState(new EndAuctionNoBids(myAgent), "end-auction-no-bids");

            // Register Transitions
            registerDefaultTransition("wait-auction", "wait-cfp");
            registerTransition("wait-cfp", "wait-proposal-reply", sendProposal);
            registerTransition("wait-cfp", "wait-cfp", highPrice);
            registerTransition("wait-cfp", "end-auction-no-bids", noBids);
            registerDefaultTransition("wait-proposal-reply", "end-auction");
        }
        
        // wait to be informed about the start of the auction and the item to bid
        private class WaitForAuction extends Behaviour {
            boolean doNotWait = false;

            WaitForAuction(Agent a) {
                super(a);
            }

            @Override
            public void action() {
                ACLMessage informMsg = myAgent.receive(msgTemp);
                if (informMsg != null) {
                    doNotWait = true;
                    try { 
                        art= (Artifact) informMsg.getContentObject();
                    } catch (UnreadableException ex) {
                        Logger.getLogger(CuratorAgent.class.getName()).log(Level.SEVERE, null, ex);
                    }
                    System.out.println(getAID().getName() + " received item to bid....");
                    msgTemp = MessageTemplate.and(
                            MessageTemplate.or(MessageTemplate.MatchPerformative(ACLMessage.CFP), MessageTemplate.MatchPerformative(ACLMessage.INFORM)),
                            MessageTemplate.MatchProtocol(informMsg.getProtocol()));
                } else {
                    block();
                }
            }

            @Override
            public boolean done() {
                return doNotWait;
            }
        }
        // receiving the call for proposal from auctioneer
        private class ReceiveCfp extends Behaviour {
            boolean receivedCfp = false;
//            int transition = error;
            int transition;

            ReceiveCfp(Agent aAgent) {
                super(aAgent);
            }

            @Override
            public void action() {
                ACLMessage cfpMsg = myAgent.receive(msgTemp);
                if (cfpMsg != null) {
                    receivedCfp = true;
                    
                    if (cfpMsg.getPerformative() == ACLMessage.CFP) {
                        price = Double.parseDouble(cfpMsg.getContent());
                        noOfBiders= Double.parseDouble(cfpMsg.getLanguage());
                        System.out.println(getAID().getName() + " received a proposal message from art agent:\n\t price is: "+ price);
                        if (trueValue <= 0) {
                            minValue = price/2;
                            maxValue= price;
                            //  get a value in the range [Min,Max):
                            trueValue= minValue + (Math.random() * (maxValue - minValue));
                        }
                        
                        System.out.println(getAID().getName() + " evaluating the price to bid....");
                        priceToBid= strategy(trueValue, noOfBiders);

                        if (priceToBid>=price) {
                            System.out.println(getAID().getName() + " wants to bid.....");
                            ACLMessage proposalMsg = new ACLMessage(ACLMessage.PROPOSE);
                            proposalMsg.addReceiver(cfpMsg.getSender());
                            proposalMsg.setProtocol(cfpMsg.getProtocol());
                            myAgent.send(proposalMsg);

                            transition = sendProposal;

                            msgTemp = MessageTemplate.and(
                                    MessageTemplate.or(MessageTemplate.MatchPerformative(ACLMessage.ACCEPT_PROPOSAL), MessageTemplate.MatchPerformative(ACLMessage.REJECT_PROPOSAL)),
                                    MessageTemplate.MatchProtocol(cfpMsg.getProtocol()));
                        } else {
                            System.out.println(getAID().getName() + " the price is too high..");
                            ACLMessage proposalMsg = new ACLMessage(ACLMessage.REFUSE);
                            proposalMsg.addReceiver(cfpMsg.getSender());
                            proposalMsg.setProtocol(cfpMsg.getProtocol());
                            myAgent.send(proposalMsg);
                            transition = highPrice;

                            msgTemp = MessageTemplate.and(
                                    MessageTemplate.or(MessageTemplate.MatchPerformative(ACLMessage.CFP), MessageTemplate.MatchPerformative(ACLMessage.INFORM)),
                                    MessageTemplate.MatchProtocol(cfpMsg.getProtocol()));
                        }
                    } else if (cfpMsg.getPerformative() == ACLMessage.INFORM) {
                        transition = noBids;
                        System.out.println(getAID().getName() + ": "+ cfpMsg.getContent());

                        msgTemp = MessageTemplate.and(MessageTemplate.MatchPerformative(ACLMessage.INFORM),
                                MessageTemplate.MatchProtocol(cfpMsg.getProtocol()));
                    }
                } else {
                    block();
                }
            }

            @Override
            public boolean done() {
                return receivedCfp;
            }

            @Override
            public int onEnd() {
                return transition;
            }

            private double strategy(double tV, double noB) {
                double priceToBid;
                if (noB<=2){
                    priceToBid=tV/2;
                    
                }else{
                   priceToBid=((noB-1)/noB)*tV; 
                }
                
                return priceToBid;   
               
            }
        }
       // receiving acceptance of the proposed bid
        private class ReceiveReply extends Behaviour {
            boolean receivedReply = false;

            ReceiveReply(Agent aAgent) {
                super(aAgent);
            }

            @Override
            public void action() {
                ACLMessage acceptanceMsg = myAgent.receive(msgTemp);
                if (acceptanceMsg != null) {
                    receivedReply = true;
                    if (acceptanceMsg.getPerformative() == ACLMessage.ACCEPT_PROPOSAL) {
                        try {
                            arts.add((Artifact) acceptanceMsg.getContentObject());
                        } catch (UnreadableException ex) {
                            Logger.getLogger(CuratorAgent.class.getName()).log(Level.SEVERE, null, ex);
                        }
                        System.out.println(getAID().getName() + ": won the auction at price "+ price);
                    } else if (acceptanceMsg.getPerformative() == ACLMessage.REJECT_PROPOSAL) {
                        System.out.println(getAID().getName() + " lost the auction.");
                    }

                    msgTemp = MessageTemplate.and(MessageTemplate.MatchPerformative(ACLMessage.INFORM),
                            MessageTemplate.MatchProtocol(acceptanceMsg.getProtocol()));
                } else {
                    block();
                }
            }

            @Override
            public boolean done() {
                return receivedReply;
            }
        }
        // end of the auction informed by auctioneer
        private class EndOfAuction extends Behaviour {
            boolean receivedReply = false;

            EndOfAuction(Agent aAgent) {
                super(aAgent);
            }

            @Override
            public void action() {
                ACLMessage replyMsg = myAgent.receive(msgTemp);
                if (replyMsg != null) {
                    receivedReply = true;
                    System.out.println(getAID().getName() + " Auction ended. ");
                    myAgent.addBehaviour(new DutchAuctionBiders());
                } else {
                    block();
                }
            }

            @Override
            public boolean done() {
                return receivedReply;
            }
        }
        // end of the auction informed by auctioneer and curator didnot make any bid
        private class EndAuctionNoBids extends Behaviour {
            EndAuctionNoBids(Agent aAgent) {
                super(aAgent);
            }

            @Override
            public void action() {
                System.out.println(getAID().getName() + ": Auction ended without bidding. ");
                myAgent.addBehaviour(new DutchAuctionBiders());
            }

            @Override
            public boolean done() {
                return true;
            }
        }
    }
}

