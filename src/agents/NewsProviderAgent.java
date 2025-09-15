package src.agents;

import jade.core.Agent;
import jade.core.AID;
import jade.core.behaviours.*;
import jade.lang.acl.ACLMessage;
import jade.domain.DFService;
import jade.domain.FIPAAgentManagement.*;
import jade.domain.FIPAException;
import java.util.*;

/**
 * News Provider Agent
 * Generates market events and news that affect trading decisions
 * Implements the 5-phase market scenario: Calm -> Trend -> Bubble -> Crash -> Recovery
 */
public class NewsProviderAgent extends Agent {
    
    // Market phases
    private enum MarketPhase {
        CALM(0, 10),           // Minutes 0-10
        EMERGING_TREND(10, 20), // Minutes 10-20
        BUBBLE(20, 35),        // Minutes 20-35
        CRASH(35, 45),         // Minutes 35-45
        STABILIZATION(45, 60); // Minutes 45-60
        
        private final int startMinute;
        private final int endMinute;
        
        MarketPhase(int start, int end) {
            this.startMinute = start;
            this.endMinute = end;
        }
    }
    
    private MarketPhase currentPhase = MarketPhase.CALM;
    private long startTime;
    private List<AID> subscribers;
    private Random random;
    
    // News templates for different phases
    private Map<MarketPhase, List<NewsTemplate>> newsTemplates;
    
    @Override
    protected void setup() {
        startTime = System.currentTimeMillis();
        subscribers = new ArrayList<>();
        random = new Random();
        
        initializeNewsTemplates();
        
        System.out.println("NewsProvider started - Market scenario controller active");
        
        // Register as news provider
        registerService();
        
        // Wait for traders to register
        addBehaviour(new WakerBehaviour(this, 2000) {
            @Override
            protected void onWake() {
                findSubscribers();
                
                // Add news generation behaviours
                addBehaviour(new PhaseControlBehaviour(myAgent, 5000)); // Check phase every 5 seconds
                addBehaviour(new NewsGenerationBehaviour(myAgent, 8000)); // Generate news every 8 seconds
                addBehaviour(new SpecialEventBehaviour(myAgent, 15000)); // Special events
            }
        });
    }
    
    private void registerService() {
        DFAgentDescription dfd = new DFAgentDescription();
        dfd.setName(getAID());
        ServiceDescription sd = new ServiceDescription();
        sd.setType("news-provider");
        sd.setName("market-news");
        dfd.addServices(sd);
        
        try {
            DFService.register(this, dfd);
        } catch (FIPAException e) {
            e.printStackTrace();
        }
    }
    
    private void findSubscribers() {
        // Find all traders to send news to
        try {
            DFAgentDescription template = new DFAgentDescription();
            ServiceDescription sd = new ServiceDescription();
            sd.setType("market-maker");
            template.addServices(sd);
            
            DFAgentDescription[] marketMakers = DFService.search(this, template);
            for (DFAgentDescription dfd : marketMakers) {
                subscribers.add(dfd.getName());
            }
            
            // Also find traders directly (they should register with market maker)
            // For now, we'll broadcast to market maker who will relay
            
        } catch (FIPAException e) {
            e.printStackTrace();
        }
    }
    
    private void initializeNewsTemplates() {
        newsTemplates = new HashMap<>();
        
        // CALM phase news
        List<NewsTemplate> calmNews = Arrays.asList(
            new NewsTemplate("NEUTRAL", "AAPL reports steady quarterly earnings meeting expectations", "LOW"),
            new NewsTemplate("POSITIVE", "Tech sector shows moderate growth in consumer confidence", "LOW"),
            new NewsTemplate("NEUTRAL", "Federal Reserve maintains current interest rate policy", "MEDIUM"),
            new NewsTemplate("POSITIVE", "AAPL announces minor product improvements", "LOW"),
            new NewsTemplate("NEUTRAL", "Market analysts maintain hold ratings on tech stocks", "LOW")
        );
        newsTemplates.put(MarketPhase.CALM, calmNews);
        
        // EMERGING TREND phase news
        List<NewsTemplate> trendNews = Arrays.asList(
            new NewsTemplate("POSITIVE", "AAPL unveils breakthrough AI technology in development", "MEDIUM"),
            new NewsTemplate("POSITIVE", "Major institutional investors increase AAPL positions", "MEDIUM"),
            new NewsTemplate("POSITIVE", "Consumer demand for AAPL products exceeds forecasts", "HIGH"),
            new NewsTemplate("POSITIVE", "AAPL partners with leading tech firms for expansion", "MEDIUM"),
            new NewsTemplate("POSITIVE", "Analysts upgrade AAPL to strong buy rating", "HIGH")
        );
        newsTemplates.put(MarketPhase.EMERGING_TREND, trendNews);
        
        // BUBBLE phase news
        List<NewsTemplate> bubbleNews = Arrays.asList(
            new NewsTemplate("POSITIVE", "AAPL stock reaches all-time high on record volume", "HIGH"),
            new NewsTemplate("POSITIVE", "Retail investors rush to buy AAPL shares", "HIGH"),
            new NewsTemplate("POSITIVE", "AAPL market cap surpasses historic milestone", "HIGH"),
            new NewsTemplate("NEUTRAL", "Market volatility increases as AAPL soars", "MEDIUM"),
            new NewsTemplate("POSITIVE", "Social media buzz drives AAPL buying frenzy", "HIGH")
        );
        newsTemplates.put(MarketPhase.BUBBLE, bubbleNews);
        
        // CRASH phase news
        List<NewsTemplate> crashNews = Arrays.asList(
            new NewsTemplate("NEGATIVE", "AAPL faces unexpected regulatory investigation", "HIGH"),
            new NewsTemplate("NEGATIVE", "Major security flaw discovered in AAPL products", "HIGH"),
            new NewsTemplate("NEGATIVE", "Key AAPL executive announces sudden departure", "HIGH"),
            new NewsTemplate("NEGATIVE", "Profit-taking triggers massive AAPL selloff", "HIGH"),
            new NewsTemplate("NEGATIVE", "Market correction fears spread to tech sector", "HIGH")
        );
        newsTemplates.put(MarketPhase.CRASH, crashNews);
        
        // STABILIZATION phase news
        List<NewsTemplate> stabilizationNews = Arrays.asList(
            new NewsTemplate("NEUTRAL", "AAPL finds support at key technical levels", "MEDIUM"),
            new NewsTemplate("POSITIVE", "Value investors begin accumulating AAPL shares", "MEDIUM"),
            new NewsTemplate("NEUTRAL", "Market volatility decreases as selling pressure eases", "LOW"),
            new NewsTemplate("POSITIVE", "AAPL management reassures investors about fundamentals", "MEDIUM"),
            new NewsTemplate("NEUTRAL", "Analysts see AAPL fairly valued at current levels", "LOW")
        );
        newsTemplates.put(MarketPhase.STABILIZATION, stabilizationNews);
    }
    
    /**
     * Controls market phase transitions
     */
    private class PhaseControlBehaviour extends TickerBehaviour {
        public PhaseControlBehaviour(Agent a, long period) {
            super(a, period);
        }
        
        @Override
        protected void onTick() {
            long elapsedMinutes = (System.currentTimeMillis() - startTime) / 60000;
            
            // Determine current phase based on elapsed time
            MarketPhase newPhase = currentPhase;
            
            if (elapsedMinutes < 10) {
                newPhase = MarketPhase.CALM;
            } else if (elapsedMinutes < 20) {
                newPhase = MarketPhase.EMERGING_TREND;
            } else if (elapsedMinutes < 35) {
                newPhase = MarketPhase.BUBBLE;
            } else if (elapsedMinutes < 45) {
                newPhase = MarketPhase.CRASH;
            } else {
                newPhase = MarketPhase.STABILIZATION;
            }
            
            // Announce phase change
            if (newPhase != currentPhase) {
                currentPhase = newPhase;
                System.out.println("=== MARKET PHASE CHANGE: " + currentPhase + " ===");
                
                // Send phase change notification
                broadcastPhaseChange();
            }
        }
        
        private void broadcastPhaseChange() {
            String announcement = "";
            
            switch (currentPhase) {
                case EMERGING_TREND:
                    announcement = "POSITIVE:Market momentum building in tech sector:IMPACT:HIGH";
                    break;
                case BUBBLE:
                    announcement = "POSITIVE:Euphoric buying reaches fever pitch:IMPACT:HIGH";
                    break;
                case CRASH:
                    announcement = "NEGATIVE:BREAKING - Market bubble bursts:IMPACT:HIGH";
                    break;
                case STABILIZATION:
                    announcement = "NEUTRAL:Markets finding equilibrium after volatility:IMPACT:MEDIUM";
                    break;
                default:
                    return;
            }
            
            broadcastNews(announcement);
        }
    }
    
    /**
     * Generates regular news based on current phase
     */
    private class NewsGenerationBehaviour extends TickerBehaviour {
        public NewsGenerationBehaviour(Agent a, long period) {
            super(a, period);
        }
        
        @Override
        protected void onTick() {
            List<NewsTemplate> phaseNews = newsTemplates.get(currentPhase);
            
            if (phaseNews != null && !phaseNews.isEmpty()) {
                // Select random news from current phase
                NewsTemplate news = phaseNews.get(random.nextInt(phaseNews.size()));
                
                // Add some randomization to the news
                String newsContent = news.toNewsString();
                
                // Occasionally skip news for realism
                if (random.nextDouble() > 0.3) { // 70% chance to publish
                    broadcastNews(newsContent);
                    
                    System.out.println("NEWS: " + news.description + 
                                     " [" + news.sentiment + "/" + news.impact + "]");
                }
            }
        }
    }
    
    /**
     * Generates special market events
     */
    private class SpecialEventBehaviour extends TickerBehaviour {
        private boolean earningsReleased = false;
        private boolean ceoStatementMade = false;
        private boolean analystDowngrade = false;
        
        public SpecialEventBehaviour(Agent a, long period) {
            super(a, period);
        }
        
        @Override
        protected void onTick() {
            long elapsedMinutes = (System.currentTimeMillis() - startTime) / 60000;
            
            // Special events at specific times
            if (elapsedMinutes >= 15 && !earningsReleased && currentPhase == MarketPhase.EMERGING_TREND) {
                broadcastNews("POSITIVE:BREAKING - AAPL beats earnings by 20%:IMPACT:HIGH");
                System.out.println("*** SPECIAL EVENT: Earnings Beat ***");
                earningsReleased = true;
            }
            
            if (elapsedMinutes >= 30 && !ceoStatementMade && currentPhase == MarketPhase.BUBBLE) {
                broadcastNews("NEUTRAL:AAPL CEO warns of overvaluation concerns:IMPACT:HIGH");
                System.out.println("*** SPECIAL EVENT: CEO Warning ***");
                ceoStatementMade = true;
            }
            
            if (elapsedMinutes >= 38 && !analystDowngrade && currentPhase == MarketPhase.CRASH) {
                broadcastNews("NEGATIVE:Major investment bank downgrades AAPL to sell:IMPACT:HIGH");
                System.out.println("*** SPECIAL EVENT: Analyst Downgrade ***");
                analystDowngrade = true;
            }
            
            // Random shock events (rare)
            if (random.nextDouble() < 0.02) { // 2% chance
                generateShockEvent();
            }
        }
        
        private void generateShockEvent() {
            String[] shockEvents = {
                "NEGATIVE:Flash crash detected in AAPL trading:IMPACT:HIGH",
                "POSITIVE:Surprise dividend announcement by AAPL:IMPACT:MEDIUM",
                "NEGATIVE:Trading halted due to unusual activity:IMPACT:HIGH",
                "POSITIVE:Major competitor faces production issues:IMPACT:MEDIUM"
            };
            
            String shock = shockEvents[random.nextInt(shockEvents.length)];
            broadcastNews(shock);
            System.out.println("*** SHOCK EVENT ***");
        }
    }
    
    /**
     * Broadcast news to all subscribers
     */
    private void broadcastNews(String newsContent) {
        ACLMessage news = new ACLMessage(ACLMessage.INFORM);
        news.setProtocol("NEWS");
        news.setContent(newsContent);
        
        // Send to all traders via market maker
        for (AID subscriber : subscribers) {
            news.addReceiver(subscriber);
        }
        
        // Also try to send directly to known trader types
        String[] traderTypes = {"ConservativeTrader", "AggressiveTrader", "FollowerTrader"};
        for (String traderType : traderTypes) {
            for (int i = 1; i <= 2; i++) {
                AID trader = new AID(traderType + "-" + i, AID.ISLOCALNAME);
                news.addReceiver(trader);
            }
        }
        
        send(news);
    }
    
    /**
     * News template helper class
     */
    private class NewsTemplate {
        String sentiment;
        String description;
        String impact;
        
        NewsTemplate(String sentiment, String description, String impact) {
            this.sentiment = sentiment;
            this.description = description;
            this.impact = impact;
        }
        
        String toNewsString() {
            return sentiment + ":" + description + ":IMPACT:" + impact;
        }
    }
    
    @Override
    protected void takeDown() {
        try {
            DFService.deregister(this);
        } catch (FIPAException e) {
            e.printStackTrace();
        }
        
        long totalMinutes = (System.currentTimeMillis() - startTime) / 60000;
        System.out.println("NewsProvider shutting down after " + totalMinutes + " minutes");
        System.out.println("Final market phase: " + currentPhase);
    }
}