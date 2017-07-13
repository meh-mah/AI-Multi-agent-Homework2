/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package Agents;

import gui.ArtistManagerFrame;
import gui.ArtistManagerInterface;
import jade.core.AID;
import jade.core.Agent;
import jade.core.behaviours.Behaviour;
import jade.core.behaviours.FSMBehaviour;
import jade.core.behaviours.OneShotBehaviour;
import jade.domain.DFService;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.ServiceDescription;
import jade.domain.FIPAException;
import jade.domain.FIPANames;
import jade.gui.GuiAgent;
import jade.gui.GuiEvent;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import jade.proto.states.MsgReceiver;
import java.awt.EventQueue;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import model.Artifact;

/**
 *
 * @author M&M
 */
public class ArtistManagerAgent extends GuiAgent {
    private ArtistManagerInterface UI;
    public static final int Kill_Agent = 0;
    public static final int Run = 1;

    @Override
    protected void setup() {

            /* Create and display the form */
            final ArtistManagerAgent agent = this;
            EventQueue.invokeLater(new Runnable() {
                @Override
                public void run() {
                    UI = new ArtistManagerFrame(agent);
                    UI.open();
                }
            });
        System.out.println("Artist Manager [" + getAID().getName() + "] is ready...");
    }

    @Override
    protected void takeDown() {
        try {
            System.out.println("Artist Manager [" + getAID().getLocalName() + "] is terminating...");
            DFService.deregister(this);
        } catch (FIPAException ex) {
            Logger.getLogger(ArtistManagerAgent.class.getName()).log(Level.SEVERE, null, ex);
        }
    }

    public void itemToAuction(String[] art) {
        String name = art[0];
        String creator = art[1];
        String description = art[2];
        String style = art[3];
        float highestPrice = Float.parseFloat(art[4]);
        float lowestPrice = Float.parseFloat(art[5]);

        Artifact a = new Artifact(name, creator, description, style);

        addBehaviour(new DutchAuctionBehaviour(this, a, highestPrice, lowestPrice));
    }
    
    @Override
    protected void onGuiEvent(GuiEvent ge) {
        switch (ge.getType()) {
            case Kill_Agent:
                UI.close();
                doDelete();
                break;
            case Run:
                String[] art = (String[]) ge.getParameter(0);
                itemToAuction(art);
                break;
        }
    }

    private class DutchAuctionBehaviour extends FSMBehaviour {

        private String cID;
        private List<AID> biders;
        private AID winnerCurator;
        private List<AID> losers = new ArrayList<>();
        private MessageTemplate pattern; 
        private double reductionRate;
        private double priceToBid;
        private int proposeCounter;
        double temp;

        public DutchAuctionBehaviour(final Agent agent, final Artifact art, final float highestPrice, final float lowestPrice) {
            super(agent);

            biders = new ArrayList<>();
            pattern= MessageTemplate.MatchProtocol(FIPANames.InteractionProtocol.FIPA_DUTCH_AUCTION);
//            cID = "auction" + System.currentTimeMillis();
//            pattern = MessageTemplate.MatchConversationId(cID);
            reductionRate = (highestPrice - lowestPrice)* 0.1;
            priceToBid = highestPrice;
            
 
            //serach for existing curators
            registerFirstState(new OneShotBehaviour(agent) {
                @Override
                public void action() {
                    UI.message("Searching for curators...");
                    DFAgentDescription template = new DFAgentDescription();
                    ServiceDescription sd = new ServiceDescription();
                    sd.setType("curator");
                    template.addServices(sd);
                    try {
                        DFAgentDescription[] result = DFService.search(myAgent, template);
                        if (result.length == 0) {
                            UI.message("No curators were found...");
                        }

                        UI.message("Following curators were found:");
                        for (int i = 0; i < result.length; i++) {
                            biders.add(result[i].getName());
                            UI.message("\t" + biders.get(i).getName());
                        }
                    } catch (FIPAException ex) {
                        Logger.getLogger(ArtistManagerAgent.class.getName()).log(Level.SEVERE, null, ex);
                    }
                }

                @Override
                public int onEnd() {
                    return biders.isEmpty() ? 0 : 1;
                }
            }, "finding biders");

            //Inform all biders about the item to bid
            registerState(new OneShotBehaviour(agent) {
                @Override
                public void action() {
                    
                    ACLMessage msg = new ACLMessage(ACLMessage.INFORM);
                    msg.setProtocol(FIPANames.InteractionProtocol.FIPA_DUTCH_AUCTION);
                    msg.setLanguage(Integer.toString(biders.size()));
                    try {
                        msg.setContentObject((Artifact) art);
                    } catch (IOException ex) {
                        Logger.getLogger(ArtistManagerAgent.class.getName()).log(Level.SEVERE, null, ex);
                    }
                    for (AID a : biders) {
                        msg.addReceiver(a);
                    }
                    myAgent.send(msg);
                    UI.message("biders informed about start of auction...");
                }
            }, "inform biders");
            //call biders to propose
            registerState(new OneShotBehaviour(agent) {
                @Override
                public void action() {
                    UI.message("Auctioneer is calling biders for proposal [price=" + priceToBid + "]...");

                    ACLMessage msg = new ACLMessage(ACLMessage.CFP);
                    msg.setProtocol(FIPANames.InteractionProtocol.FIPA_DUTCH_AUCTION);
                    msg.setLanguage(Integer.toString(biders.size()));
                    msg.setContent(Double.toString(priceToBid));
                    for (AID a: biders) {
                        msg.addReceiver(a);
                        UI.message("\tSending cfp to [" + a.getName() + "]");
                    }
                    myAgent.send(msg);
                    UI.message("Auctioneer is waiting for bid");
                    proposeCounter = 0;
                }
            }, "call for proposal");
            
            //receiving propose message from biders
            registerState(new Behaviour(agent) {
                @Override
                public void action() {
                    ACLMessage msg = myAgent.receive(pattern);
                    if (msg != null) {
                        switch (msg.getPerformative()) {
                            case ACLMessage.PROPOSE:
                                if (winnerCurator == null) {
                                    winnerCurator = msg.getSender();
                                } else {
                                    losers.add(msg.getSender());
                                }
                                break;
                            case ACLMessage.REFUSE:
                                break;
                        }
                        proposeCounter++;
                        UI.message("\t#" + proposeCounter + " [" + msg.getSender().getName() + "] responded with " + ACLMessage.getPerformative(msg.getPerformative()));
                    } else {
                        block();
                    }
                }

                @Override
                public int onEnd() {
                    return winnerCurator != null ? 1 : 0;
                }

                @Override
                public boolean done() {
                    return proposeCounter==biders.size();
                }
            }, "receiving proposals");
            
            // reducing the price if no proposal has been received
            registerState(new OneShotBehaviour(agent) {
                @Override
                public void action() {
                    UI.message("reducing price for the next round.....");
                    // reduce the price by the specified rate.
                    temp=priceToBid;
                    if(priceToBid!=lowestPrice){
                        priceToBid -= reductionRate;
                        if (priceToBid<lowestPrice){
                        priceToBid=lowestPrice;
                    }
                    }
                }

                @Override
                public int onEnd() {
                    return temp == lowestPrice ? 1 : 0;
                }
            }, "reducing price");
            // accepting received proposal if any
            registerState(new OneShotBehaviour(agent) {
                @Override
                public void action() {
                    //inform the winner
                    try {
                        ACLMessage accMsg = new ACLMessage(ACLMessage.ACCEPT_PROPOSAL);
                        accMsg.setProtocol(FIPANames.InteractionProtocol.FIPA_DUTCH_AUCTION);
                        accMsg.setContentObject(art);
                        accMsg.addReceiver(winnerCurator);
                        myAgent.send(accMsg);
                    } catch (IOException ex) {
                        Logger.getLogger(ArtistManagerAgent.class.getName()).log(Level.SEVERE, null, ex);
                    }

                    //Inform the losers whoes propose was rejected due to late response
                    ACLMessage rejMsg = new ACLMessage(ACLMessage.REJECT_PROPOSAL);
                    rejMsg.setProtocol(FIPANames.InteractionProtocol.FIPA_DUTCH_AUCTION);
                    for (AID l : losers) {
                        rejMsg.addReceiver(l);
                    }
                    myAgent.send(rejMsg);

                    UI.message("Artifact "+ art.getName()+" is soled to: "+ winnerCurator.getName()+"at price:"+ priceToBid);
                     UI.message("late responders are:\n ");
                    for (AID runnerUp : losers) {
                        UI.message("\t" + runnerUp.getName() + "\n");
                    }
                }
            }, "Accepting proposal");
            
            // closing auction if there is no curator
            registerLastState(new OneShotBehaviour(agent){
                @Override
                public void action() {
                    UI.message("Auction closed: No curator could be found\n");
                }
            }, "No bider");
            
            //end of the auction either selling or not selling the item
            registerLastState(new OneShotBehaviour(agent) {
                @Override
                public void action() {
                    //inform all biders about the end of the auction
                    ACLMessage informMsg = new ACLMessage(ACLMessage.INFORM);
                    informMsg.setProtocol(FIPANames.InteractionProtocol.FIPA_DUTCH_AUCTION);
                    informMsg.setContent("Auction ended");
                    for (AID a : biders) {
                        informMsg.addReceiver(a);
                    }
                    myAgent.send(informMsg);

                    UI.message("Auction finished: be ready for the next item....");
                }
            }, "End of the auction");

            registerTransition("finding biders", "No bider", 0);
            registerTransition("finding biders", "inform biders", 1);
            registerDefaultTransition("inform biders", "call for proposal");
            registerDefaultTransition("call for proposal", "receiving proposals");
            registerTransition("receiving proposals", "reducing price", 0);
            registerTransition("receiving proposals", "Accepting proposal", 1);
            registerTransition("reducing price", "call for proposal", 0);
            registerTransition("reducing price", "End of the auction", 1);
            registerDefaultTransition("Accepting proposal", "End of the auction");
        }
    }
}
