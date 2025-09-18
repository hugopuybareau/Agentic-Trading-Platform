package src.agents;

import jade.core.Agent;
import jade.core.AID;
import jade.core.behaviours.*;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import jade.domain.DFService;
import jade.domain.FIPAAgentManagement.*;
import jade.domain.FIPAException;
import src.model.*;
import java.util.*;

public class AggressiveTraderAgent extends Agent {
    // 🔧 REFACTORING: Utiliser Portfolio comme ConservativeTrader
    private Portfolio portfolio;
    private AID marketMaker;
    private TradingBeliefs beliefs;
    
    // Configuration de trading agressif
    private static final double MAX_POSITION_SIZE = 0.5; // 50% max par position
    private static final double MOMENTUM_THRESHOLD = 0.02;
    private static final double LEVERAGE_RATIO = 1.5; // 150% max
    private static final double MIN_CASH_RESERVE = 1000.0;

    private double initialCapital;
    private double currentPrice; 
    
    // Statistiques de trading
    private int totalTrades = 0;
    private int consecutiveWins = 0;
    private int consecutiveLosses = 0;
    private double totalProfit = 0.0;
    private List<Double> tradeProfits = new ArrayList<>();
    
    // État du trading
    private boolean isTrading = false;
    private long lastTradeTime = 0;
    private double borrowedAmount = 0.0;
    
    @Override
    protected void setup() {
        // Arguments
        Object[] args = getArguments();
        double initialCapital = 15000.0;
        
        if (args != null && args.length > 0) {
            initialCapital = (Double) args[0];
        }
        
        System.out.println("=== AGGRESSIVE TRADER SETUP ===");
        System.out.println("Agent: " + getLocalName());
        System.out.println("Initial Capital: $" + String.format("%.2f", initialCapital));
        System.out.println("Strategy: Aggressive Momentum & Leverage");
        
        // Créer Portfolio
        portfolio = new Portfolio(getLocalName());
        portfolio.addCash(initialCapital);
        this.initialCapital = initialCapital;
        
        // Initialiser beliefs
        beliefs = new TradingBeliefs();
        
        // 🔧 COPIER la logique de ConservativeTrader
        addBehaviour(new WakerBehaviour(this, 3000) {
            @Override
            protected void onWake() {
                findMarketMaker();
                
                if (marketMaker != null) {
                    System.out.println(getLocalName() + " found MarketMaker: " + marketMaker.getLocalName());
                    registerWithMarket();
                    
                    // Démarrer les behaviours
                    addBehaviour(new MarketDataReceiver());
                    addBehaviour(new OrderResponseHandler());
                    addBehaviour(new MomentumTradingBehaviour(myAgent, adjustInterval(1000)));
                    addBehaviour(new PortfolioStatusBehaviour(myAgent, adjustInterval(5000)));
                    
                    System.out.println(getLocalName() + " 🚀 Ready for aggressive trading!");
                } else {
                    System.err.println(getLocalName() + " ❌ Could not find MarketMaker!");
                }
            }
        });
        
        System.out.println(getLocalName() + " setup complete");
    }
    
    // 🔧 NOUVEAU: Méthodes Portfolio
    private double getCurrentCash() {
        return portfolio.getCash();
    }
    
    private int getSharesOwned() {
        return portfolio.getShares("AAPL");
    }
    
    private double getPortfolioValue() {
        return portfolio.getTotalValue("AAPL", currentPrice);
    }
    
    private double getInitialCapital() {
        return initialCapital;
    }
        
    private double getPortfolioReturn() {
        double currentValue = getPortfolioValue();
        return ((currentValue - initialCapital) / initialCapital) * 100; // ✅ Calculer manuellement
    }
    
    private void executeBuyOrder(int quantity, double price) {
        double totalCost = quantity * price;
        
        // ✅ CORRECTION: Utiliser Portfolio.canAfford()
        if (portfolio.canAfford("AAPL", quantity, price)) {
            ACLMessage order = new ACLMessage(ACLMessage.REQUEST);
            order.addReceiver(marketMaker);
            order.setProtocol("TRADING");
            order.setContent("BUY:AAPL:" + quantity + ":" + String.format("%.2f", price));
            send(order);
            
            System.out.println(getLocalName() + " 🚀 BUY ORDER: " + quantity + 
                            " @ $" + String.format("%.2f", price) +
                            " (Cost: $" + String.format("%.2f", totalCost) + ")");
        } else {
            System.out.println(getLocalName() + " ❌ Insufficient funds: Need $" + 
                            String.format("%.2f", totalCost) + 
                            ", Have $" + String.format("%.2f", getCurrentCash()));
        }
    }
    
    private void executeSellOrder(int quantity, double price) {
        if (getSharesOwned() >= quantity) {
            ACLMessage order = new ACLMessage(ACLMessage.REQUEST);
            order.addReceiver(marketMaker);
            order.setProtocol("TRADING");
            order.setContent("SELL:AAPL:" + quantity + ":" + String.format("%.2f", price));
            send(order);
            
            double totalRevenue = quantity * price;
            System.out.println(getLocalName() + " 💰 SELL ORDER: " + quantity + 
                            " @ $" + String.format("%.2f", price) +
                            " (Revenue: $" + String.format("%.2f", totalRevenue) + ")");
        } else {
            System.out.println(getLocalName() + " ❌ Insufficient shares: Need " + quantity + 
                            ", Have " + getSharesOwned());
        }
    }
    
    // 🔧 NOUVEAU: Gestion des réponses de trading
    private void updatePortfolioAfterTrade(String response) {
        try {
            System.out.println(getLocalName() + " 📨 Trade response: " + response);
            
            if (response.startsWith("EXECUTED:BUY:")) {
                String[] parts = response.split(":");
                if (parts.length >= 5) {
                    int quantity = Integer.parseInt(parts[2]);
                    String priceStr = parts[4].replace(",", ".");
                    double actualPrice = Double.parseDouble(priceStr);
                    
                    // ✅ CORRECTION: Utiliser Portfolio.buy()
                    boolean success = portfolio.buy("AAPL", quantity, actualPrice);
                    
                    if (success) {
                        totalTrades++;
                        lastTradeTime = System.currentTimeMillis();
                        
                        System.out.println(getLocalName() + " ✅ BUY EXECUTED:");
                        System.out.println("  Quantity: " + quantity + " shares");
                        System.out.println("  Price: $" + String.format("%.2f", actualPrice));
                        System.out.println("  Cost: $" + String.format("%.2f", quantity * actualPrice));
                        System.out.println("  New Cash: $" + String.format("%.2f", getCurrentCash()));
                        System.out.println("  Total Shares: " + getSharesOwned());
                        System.out.println("  Portfolio Value: $" + String.format("%.2f", getPortfolioValue()));
                    }
                }
            } else if (response.startsWith("EXECUTED:SELL:")) {
                String[] parts = response.split(":");
                if (parts.length >= 5) {
                    int quantity = Integer.parseInt(parts[2]);
                    String priceStr = parts[4].replace(",", ".");
                    double actualPrice = Double.parseDouble(priceStr);
                    
                    // ✅ CORRECTION: Utiliser Portfolio.sell()
                    boolean success = portfolio.sell("AAPL", quantity, actualPrice);
                    
                    if (success) {
                        totalTrades++;
                        lastTradeTime = System.currentTimeMillis();
                        
                        double revenue = quantity * actualPrice;
                        
                        System.out.println(getLocalName() + " ✅ SELL EXECUTED:");
                        System.out.println("  Quantity: " + quantity + " shares");
                        System.out.println("  Price: $" + String.format("%.2f", actualPrice));
                        System.out.println("  Revenue: $" + String.format("%.2f", revenue));
                        System.out.println("  New Cash: $" + String.format("%.2f", getCurrentCash()));
                        System.out.println("  Total Shares: " + getSharesOwned());
                        System.out.println("  Portfolio Value: $" + String.format("%.2f", getPortfolioValue()));
                    }
                }
            } else if (response.startsWith("REJECTED:")) {
                System.out.println(getLocalName() + " ❌ TRADE REJECTED: " + response);
            }
            
        } catch (Exception e) {
            System.err.println(getLocalName() + " ❌ Error updating portfolio: " + e.getMessage());
        }
    }
    
    private class OrderResponseHandler extends CyclicBehaviour {
        @Override
        public void action() {
            // Écouter les CONFIRMATIONS du MarketMaker
            MessageTemplate mt = MessageTemplate.and(
                MessageTemplate.MatchProtocol("TRADING"),
                MessageTemplate.MatchPerformative(ACLMessage.CONFIRM)
            );
            
            ACLMessage msg = receive(mt);
            if (msg != null) {
                String response = msg.getContent();
                System.out.println(getLocalName() + " Order response: " + response);
                
                try {
                    // Parse: EXECUTED:BUY:29:AAPL:100.51652480224706
                    if (response.startsWith("EXECUTED:")) {
                        String[] parts = response.split(":");
                        
                        if (parts.length >= 5) {
                            String action = parts[1];        // BUY/SELL
                            int quantity = Integer.parseInt(parts[2]);  // 29
                            String symbol = parts[3];        // AAPL
                            String priceStr = parts[4].replace(",", ".");
                            double executionPrice = Double.parseDouble(priceStr);
                            
                            // 🔧 CORRECTION CRITIQUE: Mettre à jour le portfolio local
                            if ("BUY".equals(action)) {
                                portfolio.buy(symbol, quantity, executionPrice);
                                System.out.println(getLocalName() + " ✅ LOCAL Portfolio updated: " +
                                                "Bought " + quantity + " @ $" + String.format("%.2f", executionPrice));
                            } else if ("SELL".equals(action)) {
                                portfolio.sell(symbol, quantity, executionPrice);
                                System.out.println(getLocalName() + " ✅ LOCAL Portfolio updated: " +
                                                "Sold " + quantity + " @ $" + String.format("%.2f", executionPrice));
                            }
                            
                            // Debug portfolio state
                            double currentPrice = beliefs.getCurrentPrice();
                            System.out.println(getLocalName() + " 📊 Portfolio after trade: " +
                                            "Cash=$" + String.format("%.2f", portfolio.getCash()) +
                                            ", Shares=" + portfolio.getShares(symbol) +
                                            ", Value=$" + String.format("%.2f", portfolio.getTotalValue(symbol, currentPrice)));
                        }
                    }
                } catch (Exception e) {
                    System.err.println(getLocalName() + " ❌ Error processing order response: " + e.getMessage());
                    e.printStackTrace();
                }
            } else {
                block();
            }
        }
    }

    // 🔧 REFACTORED: MarketDataReceiver avec Portfolio
    private class MarketDataReceiver extends CyclicBehaviour {
        @Override
        public void action() {
            // Market data
            MessageTemplate mt = MessageTemplate.MatchProtocol("MARKET-DATA");
            ACLMessage msg = receive(mt);
            
            if (msg != null) {
                beliefs.updateMarketData(msg.getContent());
                currentPrice = beliefs.getCurrentPrice();
            }
            
            // Trading responses
            MessageTemplate tradingTemplate = MessageTemplate.MatchProtocol("TRADING");
            ACLMessage tradingResponse = receive(tradingTemplate);
            
            if (tradingResponse != null) {
                updatePortfolioAfterTrade(tradingResponse.getContent());
            }
            
            // Trade execution updates
            MessageTemplate tradeMt = MessageTemplate.MatchProtocol("TRADE-EXECUTED");
            ACLMessage tradeMsg = receive(tradeMt);
            if (tradeMsg != null) {
                beliefs.updateTradeInfo(tradeMsg.getContent());
            }
            
            // News updates
            MessageTemplate newsMt = MessageTemplate.MatchProtocol("NEWS");
            ACLMessage newsMsg = receive(newsMt);
            if (newsMsg != null) {
                beliefs.updateNewsData(newsMsg.getContent());
                
                // React aggressively to news
                if (beliefs.hasPositiveNews() && getCurrentCash() > MIN_CASH_RESERVE) {
                    executeNewsBasedBuy();
                } else if (beliefs.hasNegativeNews() && getSharesOwned() > 0) {
                    executeEmergencySell();
                }
            }
            
            if (msg == null && tradingResponse == null && tradeMsg == null && newsMsg == null) {
                block();
            }
        }
        
        private void executeNewsBasedBuy() {
            int quantity = calculateNewsBasedQuantity();
            if (quantity > 0) {
                executeBuyOrder(quantity, beliefs.getAskPrice());
                System.out.println(getLocalName() + " 📰 NEWS-BASED BUY: " + quantity + " shares");
            }
        }
        
        private void executeEmergencySell() {
            int quantity = Math.min(getSharesOwned(), 10); // Vendre par petits lots
            executeSellOrder(quantity, beliefs.getBidPrice());
            System.out.println(getLocalName() + " 🚨 EMERGENCY SELL: " + quantity + " shares");
        }
    }
    
    // 🔧 REFACTORED: MomentumTradingBehaviour avec Portfolio
    private class MomentumTradingBehaviour extends TickerBehaviour {
        public MomentumTradingBehaviour(Agent agent, long period) {
            super(agent, period);
        }
        
        @Override
        protected void onTick() {
            if (marketMaker == null || !isTrading) return;
            
            double momentum = beliefs.getMomentum();
            double rsi = beliefs.getRSI();
            currentPrice = beliefs.getCurrentPrice();
            
            // Conditions d'achat agressives
            if (momentum > MOMENTUM_THRESHOLD && 
                rsi < 70 && 
                getCurrentCash() > MIN_CASH_RESERVE) {
                
                int quantity = calculateMomentumBuyQuantity();
                if (quantity > 0) {
                    executeBuyOrder(quantity, beliefs.getAskPrice());
                    System.out.println(getLocalName() + " 🚀 MOMENTUM BUY: " + quantity + 
                                     " shares (Momentum: " + String.format("%.4f", momentum) + ")");
                }
            }
            
            // Conditions de vente (stop-loss agressif)
            if (getSharesOwned() > 0 && 
                (momentum < -MOMENTUM_THRESHOLD || rsi > 80)) {
                
                int quantity = Math.min(getSharesOwned(), calculateMomentumSellQuantity());
                if (quantity > 0) {
                    executeSellOrder(quantity, beliefs.getBidPrice());
                    System.out.println(getLocalName() + " 💰 MOMENTUM SELL: " + quantity + 
                                     " shares (RSI: " + String.format("%.1f", rsi) + ")");
                }
            }
        }
    }
    
    // 🔧 REFACTORED: ScalpingBehaviour avec Portfolio
    private class ScalpingBehaviour extends TickerBehaviour {
        private double lastBuyPrice = 0.0;
        private int scalpingPosition = 0;
        
        public ScalpingBehaviour(Agent agent, long period) {
            super(agent, period);
        }
        
        @Override
        protected void onTick() {
            if (marketMaker == null) return;
            
            double currentPrice = beliefs.getCurrentPrice();
            double bid = beliefs.getBidPrice();
            double ask = beliefs.getAskPrice();
            double spread = ask - bid;
            
            // Scalping uniquement si spread favorable
            if (spread < currentPrice * 0.01) { // Spread < 1%
                
                // Acheter pour scalping
                if (scalpingPosition == 0 && getCurrentCash() > ask * 10) {
                    int quantity = 5; // Petites positions pour scalping
                    executeBuyOrder(quantity, ask);
                    lastBuyPrice = ask;
                    scalpingPosition = quantity;
                    
                    System.out.println(getLocalName() + " ⚡ SCALP BUY: " + quantity + 
                                     " @ $" + String.format("%.2f", ask));
                }
                
                // Vendre pour profit rapide
                else if (scalpingPosition > 0 && 
                         currentPrice > lastBuyPrice * 1.005) { // 0.5% profit
                    
                    executeSellOrder(scalpingPosition, bid);
                    
                    double profit = scalpingPosition * (bid - lastBuyPrice);
                    System.out.println(getLocalName() + " ⚡ SCALP SELL: " + scalpingPosition + 
                                     " @ $" + String.format("%.2f", bid) +
                                     " (Profit: $" + String.format("%.2f", profit) + ")");
                    
                    scalpingPosition = 0;
                    lastBuyPrice = 0.0;
                }
            }
        }
    }
    
    // 🔧 REFACTORED: LeverageManagementBehaviour avec Portfolio
    private class LeverageManagementBehaviour extends TickerBehaviour {
        public LeverageManagementBehaviour(Agent agent, long period) {
            super(agent, period);
        }
        
        @Override
        protected void onTick() {
            double portfolioValue = getPortfolioValue();
            double leverageRatio = portfolioValue / getInitialCapital();
            
            // Gestion du leverage
            if (leverageRatio > LEVERAGE_RATIO) {
                // Réduire la position
                int sharesToSell = Math.min(getSharesOwned(), 
                                          (int)((leverageRatio - LEVERAGE_RATIO) * getSharesOwned()));
                if (sharesToSell > 0) {
                    executeSellOrder(sharesToSell, beliefs.getBidPrice());
                    System.out.println(getLocalName() + " ⚠️ LEVERAGE REDUCTION: Sold " + 
                                     sharesToSell + " shares");
                }
            }
            
            // Gestion du cash minimum
            if (getCurrentCash() < MIN_CASH_RESERVE && getSharesOwned() > 0) {
                int sharesToSell = Math.min(5, getSharesOwned());
                executeSellOrder(sharesToSell, beliefs.getBidPrice());
                System.out.println(getLocalName() + " 💰 CASH RESERVE: Sold " + 
                                 sharesToSell + " shares for liquidity");
            }
        }
    }
    
    // 🔧 NOUVEAU: PortfolioStatusBehaviour
    private class PortfolioStatusBehaviour extends TickerBehaviour {
        public PortfolioStatusBehaviour(Agent agent, long period) {
            super(agent, period);
        }
        
        @Override
        protected void onTick() {
            System.out.println("\n=== " + getLocalName() + " STATUS ===");
            System.out.println("Portfolio Value: $" + String.format("%.2f", getPortfolioValue()));
            System.out.println("Cash: $" + String.format("%.2f", getCurrentCash()));
            System.out.println("Shares: " + getSharesOwned());
            System.out.println("Return: " + String.format("%.2f", getPortfolioReturn()) + "%");
            System.out.println("Total Trades: " + totalTrades);
            System.out.println("Borrowed: $" + String.format("%.2f", borrowedAmount));
            System.out.println("Net Value: $" + String.format("%.2f", getPortfolioValue() - borrowedAmount));
            System.out.println("Strategy: Aggressive Momentum & Leverage");
            
            if (consecutiveWins > 0) {
                System.out.println("Streak: " + consecutiveWins + " wins 🔥");
            } else if (consecutiveLosses > 0) {
                System.out.println("Streak: " + consecutiveLosses + " losses ❄️");
            }
        }
    }
    
    // 🔧 HELPER METHODS
    private int calculateMomentumBuyQuantity() {
        double availableCash = getCurrentCash();
        double maxInvestment = availableCash * MAX_POSITION_SIZE;
        
        // Boost pour winning streak
        if (consecutiveWins > 2) {
            maxInvestment *= 1.2; // 20% boost
        }
        
        int quantity = (int)(maxInvestment / currentPrice);
        return Math.max(1, Math.min(20, quantity));
    }
    
    private int calculateMomentumSellQuantity() {
        int shares = getSharesOwned();
        return Math.min(shares, Math.max(1, shares / 3)); // Vendre par tiers
    }
    
    private int calculateNewsBasedQuantity() {
        double availableCash = getCurrentCash();
        double maxInvestment = availableCash * 0.15; // 15% pour news
        
        int quantity = (int)(maxInvestment / beliefs.getAskPrice());
        return Math.max(1, Math.min(10, quantity));
    }
    
    private long adjustInterval(long originalInterval) {
        int acceleration = getAccelerationFactor();
        return Math.max(50, originalInterval / acceleration);
    }
    
    private int getAccelerationFactor() {
        try {
            return Integer.parseInt(System.getProperty("trading.acceleration", "1"));
        } catch (Exception e) {
            return 1;
        }
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
        try {
             ACLMessage register = new ACLMessage(ACLMessage.REQUEST);
            register.addReceiver(marketMaker);
            register.setProtocol("PORTFOLIO");
            register.setContent("REGISTER:" + initialCapital);
            send(register);
            isTrading=true;
            System.out.println(getLocalName() + " registration request sent");
        } catch (Exception e) {
            System.err.println(getLocalName() + " error registering: " + e.getMessage());
        }
    }
    
    @Override
    protected void takeDown() {
        System.out.println("\n=== " + getLocalName() + " FINAL REPORT ===");
        System.out.println("Initial Capital: $" + String.format("%.2f", getInitialCapital()));
        System.out.println("Final Portfolio: $" + String.format("%.2f", getPortfolioValue()));
        System.out.println("Outstanding Debt: $" + String.format("%.2f", borrowedAmount));
        System.out.println("Net Value: $" + String.format("%.2f", getPortfolioValue() - borrowedAmount));
        System.out.println("Total Return: " + String.format("%.2f", getPortfolioReturn()) + "%");
        System.out.println("Total Trades: " + totalTrades);
        System.out.println("Strategy: Aggressive Momentum & Leverage");
        
        if (totalTrades > 0) {
            double winRate = (consecutiveWins / (double) totalTrades) * 100;
            System.out.println("Estimated Win Rate: " + String.format("%.1f", winRate) + "%");
        }
        
        System.out.println("=====================================");
    }
}