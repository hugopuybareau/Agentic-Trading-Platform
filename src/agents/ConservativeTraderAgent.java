package src.agents;

import jade.core.Agent;
import jade.core.AID;
import jade.core.behaviours.*;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import jade.domain.DFService;
import jade.domain.FIPAAgentManagement.*;
import jade.domain.FIPAException;
import src.models.*;

/**
 * Agent trader conservateur utilisant l'approche BDI.
 * Strategie: investissement de valeur, faible risque, preservation du capital.
 */
public class ConservativeTraderAgent extends Agent {
    
    // Composants BDI (Beliefs-Desires-Intentions)
    private TradingBeliefs beliefs;
    private ConservativeDesires desires;
    private TradingIntentions intentions;
    
    private double initialCapital;
    private Portfolio portfolio;
    
    // Gestion du risque conservatrice
    private final double MAX_POSITION_SIZE = 0.3; // Max 30% du capital par trade
    private final double STOP_LOSS = 0.05;
    private final double TAKE_PROFIT = 0.10;
    
    private int tradingCooldown = 0;
    private final int MIN_TRADE_INTERVAL = 5;
    private String stockSymbol = "AAPL";

    // Garde-fous contre le sur-trading
    private long lastTradeRealMs = 0L;
    private boolean hasPendingOrder = false;
    private int tradesThisSession = 0;
    private final int maxTradesPerSimHour = 60;
    
    private static int accel() { 
        try { 
            return Integer.parseInt(System.getProperty("trading.acceleration","1")); 
        } catch(Exception e) {
            return 1;
        } 
    }
    
    private static long simSecondsToRealMs(int sec) { 
        return (sec * 1000L) / Math.max(1, accel()); 
    }
    
    private final long minCooldownRealMs = simSecondsToRealMs(120); // 2 min simulees

    private AID marketMaker;
    
    @Override
    protected void setup() {
        Object[] args = getArguments();
        if (args != null && args.length > 0) {
            initialCapital = (Double) args[0];
        } else {
            initialCapital = 10000.0;
        }
        
        // Initialisation des composants BDI et du portfolio
        portfolio = new Portfolio(getLocalName());
        portfolio.addCash(initialCapital);
        beliefs = new TradingBeliefs();
        desires = new ConservativeDesires();
        intentions = new TradingIntentions();
        
        System.out.println("Conservative Trader " + getLocalName() + 
                         " started with capital: $" + initialCapital);
        
        // Recherche du MarketMaker et initialisation des comportements
        addBehaviour(new WakerBehaviour(this, 3000) {
            @Override
            protected void onWake() {
                findMarketMaker();
                
                if (marketMaker != null) {
                    registerWithMarket();
                    subscribeMarketData();
                    
                    addBehaviour(new MarketDataReceiver());
                    addBehaviour(new ConservativeTradingBehaviour(myAgent, adjustInterval(3000)));
                    addBehaviour(new RiskManagementBehaviour(myAgent, adjustInterval(5000)));
                    addBehaviour(new OrderResponseHandler());
                    addBehaviour(new NewsListener()); 
                } else {
                    System.err.println(getLocalName() + ": MarketMaker not found!");
                }
            }
        });
    }
    
    private void subscribeMarketData() {
        try {
            ACLMessage sub = new ACLMessage(ACLMessage.SUBSCRIBE);
            sub.setProtocol("MARKET-DATA-SUB");
            sub.addReceiver(marketMaker);
            sub.setContent(stockSymbol);
            send(sub);
            System.out.println(getLocalName() + " -> SUBSCRIBE MARKET-DATA for " + stockSymbol);
        } catch (Exception e) {
            System.err.println(getLocalName() + " subscribe error: " + e.getMessage());
        }
    }
    
    private int getAccelerationFactor() {
        return Integer.parseInt(System.getProperty("trading.acceleration", "1"));
    }

    private long adjustInterval(long originalInterval) {
        return originalInterval / getAccelerationFactor();
    }
        
    private void findMarketMaker() {
        try {
            DFAgentDescription template = new DFAgentDescription();
            ServiceDescription sd = new ServiceDescription();
            sd.setType("market-maker");
            template.addServices(sd);
            
            DFAgentDescription[] result = DFService.search(this, template);
            if (result.length > 0) {
                marketMaker = result[0].getName();
                System.out.println(getLocalName() + " found MarketMaker: " + marketMaker.getLocalName());
            }
        } catch (FIPAException e) {
            e.printStackTrace();
        }
    }
    
    private void registerWithMarket() {
        ACLMessage register = new ACLMessage(ACLMessage.REQUEST);
        register.addReceiver(marketMaker);
        register.setProtocol("PORTFOLIO");
        register.setContent("REGISTER:" + initialCapital);
        send(register);
        System.out.println(getLocalName() + " registration request sent");
    }
    
    /**
     * Desirs conservateurs - Objectifs d'investissement prudents.
     */
    private class ConservativeDesires {
        
        public boolean wantsToBuy() {
            if (beliefs.getCurrentPrice() <= 0 || portfolio.getCash() < beliefs.getCurrentPrice() * 10) {
                return false;
            }
            
            boolean noShares = portfolio.getShares(stockSymbol) == 0;
            boolean oversold = beliefs.isOversold(); // RSI < 30
            boolean goodValue = beliefs.getCurrentPrice() < beliefs.getMovingAverage20();
            boolean noNegativeNews = !beliefs.hasNegativeNews();
            
            // Conservateur: acheter seulement si toutes les conditions sont favorables
            return noShares && oversold && goodValue && noNegativeNews;
        }
        
        public boolean wantsToSell() {
            int currentShares = portfolio.getShares(stockSymbol);
            if (currentShares == 0) return false;
            
            boolean overbought = beliefs.isOverbought(); // RSI > 70
            boolean belowMA = beliefs.getCurrentPrice() < beliefs.getMovingAverage20();
            boolean negativeNews = beliefs.hasNegativeNews();
            boolean bearishMarket = beliefs.getMarketSentiment().equals("BEARISH");
            
            // Conservateur: vendre des que les conditions deviennent defavorables
            return overbought || belowMA || negativeNews || bearishMarket;
        }
    }

    /**
     * Intentions de trading - Plans d'action concrets.
     */
    private class TradingIntentions {
        
        public int calculateBuyQuantity() {
            double maxInvestment = portfolio.getCash() * MAX_POSITION_SIZE;
            double currentPrice = beliefs.getCurrentPrice();
            
            if (currentPrice <= 0) return 0;
            
            int quantity = (int)(maxInvestment / currentPrice);
            
            // Minimum 5 actions pour que ce soit significatif
            if (quantity < 5) {
                quantity = Math.min(5, (int)(portfolio.getCash() / currentPrice));
            }
            
            return quantity;
        }
        
        public int calculateSellQuantity() {
            int currentShares = portfolio.getShares(stockSymbol);
            // Vendre 25% des positions (approche prudente)
            return Math.max(1, currentShares / 4);
        }
        
        public boolean shouldExecuteTrade() {
            return tradingCooldown == 0 && beliefs.getCurrentPrice() > 0;
        }
    }
    
    // Reception et traitement des donnees de marche
    private class MarketDataReceiver extends CyclicBehaviour {
        @Override
        public void action() {
            MessageTemplate mt = MessageTemplate.and(
                MessageTemplate.MatchProtocol("MARKET-DATA"),
                MessageTemplate.MatchPerformative(ACLMessage.INFORM)
            );
            ACLMessage msg = receive(mt);
            
            if (msg != null) {
                String content = msg.getContent();
                
                if (content.startsWith("PRICE:")) {
                    beliefs.updateMarketData(content);
                    
                    if (Math.random() < 0.05) { // Log occasionnel
                        System.out.println(getLocalName() + " received market data: Price=$" + 
                                         String.format("%.2f", beliefs.getCurrentPrice()));
                    }
                } else if (content.startsWith("TRADE:")) {
                    beliefs.updateTradeInfo(content);
                }
            } else {
                block();
            }
        }
    }

    // Gestion des confirmations d'ordres
    private class OrderResponseHandler extends CyclicBehaviour {
        @Override
        public void action() {
            MessageTemplate mt = MessageTemplate.MatchProtocol("TRADING");
            ACLMessage msg = receive(mt);

            if (msg != null) {
                try {
                    if (msg.getPerformative() == ACLMessage.CONFIRM) {
                        String response = msg.getContent(); // EXECUTED:BUY:QTY:SYMB:PRICE
                        String[] parts = response.split(":");
                        if (parts.length >= 5 && "EXECUTED".equals(parts[0])) {
                            String action = parts[1];
                            int quantity = Integer.parseInt(parts[2]);
                            String symbol = parts[3];
                            double executionPrice = Double.parseDouble(parts[4].replace(",", "."));

                            if ("BUY".equals(action)) {
                                portfolio.buy(symbol, quantity, executionPrice);
                            } else if ("SELL".equals(action)) {
                                portfolio.sell(symbol, quantity, executionPrice);
                            }

                            tradesThisSession++;
                            lastTradeRealMs = System.currentTimeMillis();

                            double curPx = beliefs.getCurrentPrice();
                            System.out.println(getLocalName() + " EXECUTED " + action +
                                " " + quantity + " " + symbol + " @ $" + String.format("%.2f", executionPrice) +
                                " | Cash=$" + String.format("%.2f", portfolio.getCash()) +
                                " | Holdings=" + portfolio.getShares(symbol) +
                                " | Value=$" + String.format("%.2f", portfolio.getTotalValue(symbol, curPx)));
                        }
                    } else if (msg.getPerformative() == ACLMessage.REFUSE ||
                               msg.getPerformative() == ACLMessage.FAILURE) {
                        System.out.println(getLocalName() + " Order rejected: " + msg.getContent());
                    }
                } catch (Exception e) {
                    System.err.println(getLocalName() + " order handling error: " + e.getMessage());
                } finally {
                    hasPendingOrder = false;
                }
            } else {
                block();
            }
        }
    }
    
    /**
     * Comportement de trading principal - Strategie conservatrice.
     */
    private class ConservativeTradingBehaviour extends TickerBehaviour {
        public ConservativeTradingBehaviour(Agent a, long period) {
            super(a, period);
        }
        
        @Override
        protected void onTick() {
            if (beliefs.getCurrentPrice() <= 0) {
                return;
            }

            // Garde-fous pour limiter le nombre de trades
            long now = System.currentTimeMillis();
            if (hasPendingOrder) return;
            if (now - lastTradeRealMs < minCooldownRealMs) return;
            if (tradesThisSession >= maxTradesPerSimHour) return;

            if (desires.wantsToBuy()) {
                executeBuyOrder();
            } else if (desires.wantsToSell()) {
                executeSellOrder();
            }
        }
        
        private void executeBuyOrder() {
            int quantity = intentions.calculateBuyQuantity();
            double px = beliefs.getCurrentPrice();
            if (quantity > 0 && portfolio.getCash() >= quantity * px) {
                ACLMessage order = new ACLMessage(ACLMessage.REQUEST);
                order.addReceiver(marketMaker);
                order.setProtocol("TRADING");
                order.setContent("BUY:" + stockSymbol + ":" + quantity + ":" + px);
                send(order);

                hasPendingOrder = true;

                System.out.println(getLocalName() + " SENT BUY ORDER: " + quantity +
                    " @ $" + String.format("%.2f", px));
            }
        }
        
        private void executeSellOrder() {
            int currentShares = portfolio.getShares(stockSymbol);
            int quantity = Math.min(intentions.calculateSellQuantity(), currentShares);
            double px = beliefs.getCurrentPrice();
            if (quantity > 0) {
                ACLMessage order = new ACLMessage(ACLMessage.REQUEST);
                order.addReceiver(marketMaker);
                order.setProtocol("TRADING");
                order.setContent("SELL:" + stockSymbol + ":" + quantity + ":" + px);
                send(order);

                hasPendingOrder = true;

                System.out.println(getLocalName() + " SENT SELL ORDER: " + quantity +
                    " @ $" + String.format("%.2f", px));
            }
        }
    }
    
    // Gestion du risque et surveillance du portfolio
    private class RiskManagementBehaviour extends TickerBehaviour {
        public RiskManagementBehaviour(Agent a, long period) {
            super(a, period);
        }
        
        @Override
        protected void onTick() {
            double currentPrice = beliefs.getCurrentPrice();
            if (currentPrice <= 0) return;
            
            double portfolioValue = portfolio.getTotalValue(stockSymbol, currentPrice);
            double totalReturn = (portfolioValue - initialCapital) / initialCapital;
            
            System.out.println(getLocalName() + " Portfolio Status: " +
                             "Value: $" + String.format("%.2f", portfolioValue) +
                             ", Return: " + String.format("%.2f%%", totalReturn * 100) +
                             ", Shares: " + portfolio.getShares(stockSymbol) +
                             ", Cash: $" + String.format("%.2f", portfolio.getCash()));
        }
    }

    // Ecoute et traitement des actualites financieres
    private class NewsListener extends CyclicBehaviour {
        @Override
        public void action() {
            MessageTemplate mt = MessageTemplate.MatchProtocol("NEWS");
            ACLMessage msg = receive(mt);
            
            if (msg != null) {
                String newsContent = msg.getContent();
                beliefs.updateNewsData(newsContent);
                
                System.out.println(getLocalName() + " News processed: " + newsContent);
                reactToNewsImpact();
            } else {
                block();
            }
        }
        
        private void reactToNewsImpact() {
            double newsImpact = beliefs.getNewsImpact();
            
            if (beliefs.hasNegativeNews()) {
                System.out.println(getLocalName() + " Negative news detected, becoming more cautious");
            } else if (beliefs.hasPositiveNews()) {
                System.out.println(getLocalName() + " Positive news detected, slightly more optimistic");
            }
            
            System.out.println(getLocalName() + " News impact: " + String.format("%.2f", newsImpact));
        }
    }
    
    @Override
    protected void takeDown() {
        double currentPrice = beliefs.getCurrentPrice() > 0 ? beliefs.getCurrentPrice() : 100.0;
        double finalValue = portfolio.getTotalValue(stockSymbol, currentPrice);
        double totalReturn = (finalValue - initialCapital) / initialCapital;
        
        System.out.println("=== " + getLocalName() + " Final Report ===");
        System.out.println("Initial Capital: $" + initialCapital);
        System.out.println("Final Value: $" + String.format("%.2f", finalValue));
        System.out.println("Total Return: " + String.format("%.2f%%", totalReturn * 100));
        System.out.println("Strategy: Conservative Value Investing");
    }
}