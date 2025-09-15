package src.agents;

import jade.core.Agent;
import jade.core.AID;
import jade.core.behaviours.*;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import jade.domain.DFService;
import jade.domain.FIPAAgentManagement.*;
import jade.domain.FIPAException;
import model.*;

/**
 * Aggressive Trader Agent - BDI Architecture
 * High risk/high reward strategy with momentum trading and leverage
 * Target: >20% returns, accepts high volatility
 */
public class AggressiveTraderAgent extends Agent {
    
    // BDI Components
    private TradingBeliefs beliefs;
    private AggressiveDesires desires;
    private TradingIntentions intentions;
    
    // Portfolio Management
    private double initialCapital;
    private double currentCash;
    private int sharesOwned;
    private double lastTradePrice;
    
    // Leverage and Risk
    private final double MAX_LEVERAGE = 2.0; // Can borrow up to 100% of capital
    private double borrowedAmount = 0;
    private final double MARGIN_CALL_LEVEL = -0.25; // -25% triggers margin call
    
    // Trading Parameters
    private final double MAX_POSITION_SIZE = 0.5; // 50% of capital per trade
    private final double MOMENTUM_THRESHOLD = 0.02; // 2% price movement triggers action
    private int consecutiveWins = 0;
    private int consecutiveLosses = 0;
    
    // High-frequency trading
    private int rapidTradeCount = 0;
    private long lastTradeTime = 0;
    
    private AID marketMaker;
    
    @Override
    protected void setup() {
        // Initialize capital
        Object[] args = getArguments();
        if (args != null && args.length > 0) {
            initialCapital = (Double) args[0];
        } else {
            initialCapital = 15000.0;
        }
        currentCash = initialCapital;
        sharesOwned = 0;
        
        // Initialize BDI components
        beliefs = new TradingBeliefs();
        desires = new AggressiveDesires();
        intentions = new TradingIntentions();
        
        System.out.println("Aggressive Trader " + getLocalName() + 
                         " started with capital: $" + initialCapital);
        
        // Find market maker
        addBehaviour(new WakerBehaviour(this, 1000) {
            @Override
            protected void onWake() {
                findMarketMaker();
                
                if (marketMaker != null) {
                    registerWithMarket();
                    
                    // Add aggressive trading behaviours
                    addBehaviour(new MarketDataReceiver());
                    addBehaviour(new MomentumTradingBehaviour(myAgent, 1000)); // Fast trading
                    addBehaviour(new ScalpingBehaviour(myAgent, 500)); // Ultra-fast scalping
                    addBehaviour(new LeverageManagementBehaviour(myAgent, 5000));
                }
            }
        });
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
        register.setContent("REGISTER:" + currentCash);
        send(register);
    }
    
    /**
     * Aggressive Desires - High risk appetite
     */
    private class AggressiveDesires {
        
        public boolean wantsToGoLong() {
            // Buy aggressively on momentum
            return beliefs.getMomentum() > MOMENTUM_THRESHOLD &&
                   beliefs.isBullishTrend() &&
                   beliefs.getVolume() > 100 && // High volume
                   (beliefs.hasPositiveNews() || consecutiveWins > 1);
        }
        
        public boolean wantsToGoShort() {
            // Sell aggressively on negative momentum
            return sharesOwned > 0 &&
                   (beliefs.getMomentum() < -MOMENTUM_THRESHOLD ||
                    beliefs.hasNegativeNews() ||
                    consecutiveLosses > 2);
        }
        
        public boolean wantsToScalp() {
            // Quick in-and-out trades on small movements
            return Math.abs(beliefs.getPriceChangePercent()) > 0.5 &&
                   beliefs.getVolatility() > 0.02 &&
                   rapidTradeCount < 10; // Limit rapid trades
        }
        
        public boolean wantsToUseLeverage() {
            // Use leverage when confident
            return beliefs.isBullishTrend() &&
                   consecutiveWins > 2 &&
                   borrowedAmount < initialCapital * (MAX_LEVERAGE - 1);
        }
    }
    
    /**
     * Trading Intentions - Aggressive execution
     */
    private class TradingIntentions {
        
        public int calculateAggressiveBuyQuantity() {
            double availableFunds = currentCash + getMaxBorrowingCapacity();
            double maxInvestment = availableFunds * MAX_POSITION_SIZE;
            
            // Increase position size with confidence
            if (consecutiveWins > 2) {
                maxInvestment *= 1.5; // 50% larger positions when winning
            }
            
            int quantity = (int)(maxInvestment / beliefs.getAskPrice());
            
            // Minimum aggressive position
            return Math.max(50, quantity);
        }
        
        public int calculateAggressiveSellQuantity() {
            // Sell all or nothing approach
            if (beliefs.hasNegativeNews() || consecutiveLosses > 2) {
                return sharesOwned; // Panic sell
            }
            
            // Take quick profits
            double priceChange = beliefs.getCurrentPrice() - lastTradePrice;
            if (priceChange > lastTradePrice * 0.03) { // 3% profit
                return sharesOwned / 2; // Take half profit
            }
            
            return Math.max(20, sharesOwned / 3);
        }
        
        public int calculateScalpQuantity() {
            // Large positions for small movements
            double availableFunds = currentCash * 0.3; // 30% for scalping
            return (int)(availableFunds / beliefs.getCurrentPrice());
        }
        
        private double getMaxBorrowingCapacity() {
            double portfolioValue = currentCash + (sharesOwned * beliefs.getCurrentPrice());
            double maxBorrow = portfolioValue * (MAX_LEVERAGE - 1);
            return Math.max(0, maxBorrow - borrowedAmount);
        }
    }
    
    /**
     * Market data receiver
     */
    private class MarketDataReceiver extends CyclicBehaviour {
        @Override
        public void action() {
            MessageTemplate mt = MessageTemplate.MatchProtocol("MARKET-DATA");
            ACLMessage msg = receive(mt);
            
            if (msg != null) {
                beliefs.updateMarketData(msg.getContent());
                
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
                    beliefs.updateNews(newsMsg.getContent());
                    
                    // React immediately to news
                    if (beliefs.hasNegativeNews() && sharesOwned > 0) {
                        // Emergency sell
                        executeImmediateSell();
                    }
                }
            } else {
                block();
            }
        }
        
        private void executeImmediateSell() {
            ACLMessage order = new ACLMessage(ACLMessage.REQUEST);
            order.addReceiver(marketMaker);
            order.setProtocol("TRADING");
            order.setContent("SELL:AAPL:" + sharesOwned + ":SHARES:MARKET_PRICE");
            send(order);
            
            System.out.println(getLocalName() + " EMERGENCY SELL on negative news!");
            currentCash += sharesOwned * beliefs.getBidPrice();
            sharesOwned = 0;
        }
    }
    
    /**
     * Momentum trading behaviour
     */
    private class MomentumTradingBehaviour extends TickerBehaviour {
        public MomentumTradingBehaviour(Agent a, long period) {
            super(a, period);
        }
        
        @Override
        protected void onTick() {
            long currentTime = System.currentTimeMillis();
            
            // Throttle trading frequency
            if (currentTime - lastTradeTime < 2000) {
                return; // Wait at least 2 seconds between trades
            }
            
            if (desires.wantsToGoLong()) {
                executeMomentumBuy();
            } else if (desires.wantsToGoShort()) {
                executeMomentumSell();
            }
            
            // Use leverage if appropriate
            if (desires.wantsToUseLeverage()) {
                useLeverage();
            }
        }
        
        private void executeMomentumBuy() {
            int quantity = intentions.calculateAggressiveBuyQuantity();
            
            // Check if we need to borrow
            double cost = quantity * beliefs.getAskPrice();
            if (cost > currentCash) {
                double toBorrow = cost - currentCash;
                borrowedAmount += toBorrow;
                currentCash += toBorrow;
                System.out.println(getLocalName() + " using LEVERAGE: $" + 
                                 String.format("%.2f", toBorrow));
            }
            
            if (quantity > 0 && currentCash >= cost) {
                ACLMessage order = new ACLMessage(ACLMessage.REQUEST);
                order.addReceiver(marketMaker);
                order.setProtocol("TRADING");
                order.setContent("BUY:AAPL:" + quantity + ":SHARES:MARKET_PRICE");
                send(order);
                
                sharesOwned += quantity;
                currentCash -= cost;
                lastTradePrice = beliefs.getAskPrice();
                lastTradeTime = System.currentTimeMillis();
                rapidTradeCount++;
                
                System.out.println(getLocalName() + " MOMENTUM BUY " + quantity + 
                                 " shares @ $" + String.format("%.2f", lastTradePrice) +
                                 " (Momentum: " + String.format("%.2f%%", beliefs.getMomentum() * 100) + ")");
            }
        }
        
        private void executeMomentumSell() {
            int quantity = intentions.calculateAggressiveSellQuantity();
            quantity = Math.min(quantity, sharesOwned);
            
            if (quantity > 0) {
                ACLMessage order = new ACLMessage(ACLMessage.REQUEST);
                order.addReceiver(marketMaker);
                order.setProtocol("TRADING");
                order.setContent("SELL:AAPL:" + quantity + ":SHARES:MARKET_PRICE");
                send(order);
                
                double price = beliefs.getBidPrice();
                double profit = (price - lastTradePrice) * quantity;
                
                sharesOwned -= quantity;
                currentCash += quantity * price;
                lastTradeTime = System.currentTimeMillis();
                
                // Track performance
                if (profit > 0) {
                    consecutiveWins++;
                    consecutiveLosses = 0;
                } else {
                    consecutiveLosses++;
                    consecutiveWins = 0;
                }
                
                System.out.println(getLocalName() + " MOMENTUM SELL " + quantity + 
                                 " shares @ $" + String.format("%.2f", price) +
                                 " (P&L: $" + String.format("%.2f", profit) + ")");
            }
        }
        
        private void useLeverage() {
            // Already handled in buy logic
        }
    }
    
    /**
     * Scalping behaviour for quick profits
     */
    private class ScalpingBehaviour extends TickerBehaviour {
        private double scalpEntryPrice = 0;
        private int scalpPosition = 0;
        
        public ScalpingBehaviour(Agent a, long period) {
            super(a, period);
        }
        
        @Override
        protected void onTick() {
            if (!desires.wantsToScalp()) {
                return;
            }
            
            // Enter scalp position
            if (scalpPosition == 0 && beliefs.getPriceChangePercent() > 0.5) {
                int quantity = intentions.calculateScalpQuantity();
                if (quantity > 0 && currentCash >= quantity * beliefs.getAskPrice()) {
                    ACLMessage order = new ACLMessage(ACLMessage.REQUEST);
                    order.addReceiver(marketMaker);
                    order.setProtocol("TRADING");
                    order.setContent("BUY:AAPL:" + quantity + ":SHARES:MARKET_PRICE");
                    send(order);
                    
                    scalpPosition = quantity;
                    scalpEntryPrice = beliefs.getAskPrice();
                    currentCash -= quantity * scalpEntryPrice;
                    sharesOwned += quantity;
                    
                    System.out.println(getLocalName() + " SCALP ENTRY " + quantity + 
                                     " shares @ $" + String.format("%.2f", scalpEntryPrice));
                }
            }
            
            // Exit scalp position on small profit
            if (scalpPosition > 0) {
                double profitPercent = (beliefs.getBidPrice() - scalpEntryPrice) / scalpEntryPrice;
                
                if (profitPercent > 0.005 || profitPercent < -0.003) { // 0.5% profit or 0.3% loss
                    ACLMessage order = new ACLMessage(ACLMessage.REQUEST);
                    order.addReceiver(marketMaker);
                    order.setProtocol("TRADING");
                    order.setContent("SELL:AAPL:" + scalpPosition + ":SHARES:MARKET_PRICE");
                    send(order);
                    
                    double exitPrice = beliefs.getBidPrice();
                    double profit = (exitPrice - scalpEntryPrice) * scalpPosition;
                    
                    currentCash += scalpPosition * exitPrice;
                    sharesOwned -= scalpPosition;
                    
                    System.out.println(getLocalName() + " SCALP EXIT @ $" + 
                                     String.format("%.2f", exitPrice) +
                                     " (Profit: $" + String.format("%.2f", profit) + ")");
                    
                    scalpPosition = 0;
                    scalpEntryPrice = 0;
                }
            }
        }
    }
    
    /**
     * Leverage and margin management
     */
    private class LeverageManagementBehaviour extends TickerBehaviour {
        public LeverageManagementBehaviour(Agent a, long period) {
            super(a, period);
        }
        
        @Override
        protected void onTick() {
            double portfolioValue = currentCash + (sharesOwned * beliefs.getCurrentPrice());
            double netValue = portfolioValue - borrowedAmount;
            double returnPercent = (netValue - initialCapital) / initialCapital;
            
            // Log aggressive trading status
            System.out.println(getLocalName() + " Status: " +
                             "Portfolio: $" + String.format("%.2f", portfolioValue) +
                             ", Debt: $" + String.format("%.2f", borrowedAmount) +
                             ", Return: " + String.format("%.2f%%", returnPercent * 100) +
                             ", Wins: " + consecutiveWins + ", Losses: " + consecutiveLosses);
            
            // Margin call check
            if (returnPercent < MARGIN_CALL_LEVEL && borrowedAmount > 0) {
                System.out.println(getLocalName() + " MARGIN CALL! Liquidating positions!");
                
                // Forced liquidation
                if (sharesOwned > 0) {
                    ACLMessage order = new ACLMessage(ACLMessage.REQUEST);
                    order.addReceiver(marketMaker);
                    order.setProtocol("TRADING");
                    order.setContent("SELL:AAPL:" + sharesOwned + ":SHARES:MARKET_PRICE");
                    send(order);
                    
                    currentCash += sharesOwned * beliefs.getBidPrice();
                    sharesOwned = 0;
                    
                    // Pay back debt
                    double repayment = Math.min(borrowedAmount, currentCash);
                    borrowedAmount -= repayment;
                    currentCash -= repayment;
                }
            }
            
            // Reset rapid trade counter periodically
            rapidTradeCount = Math.max(0, rapidTradeCount - 1);
        }
    }
    
    @Override
    protected void takeDown() {
        double portfolioValue = currentCash + (sharesOwned * beliefs.getCurrentPrice());
        double netValue = portfolioValue - borrowedAmount;
        double totalReturn = (netValue - initialCapital) / initialCapital;
        
        System.out.println("=== " + getLocalName() + " Final Report ===");
        System.out.println("Initial Capital: $" + initialCapital);
        System.out.println("Final Portfolio: $" + String.format("%.2f", portfolioValue));
        System.out.println("Outstanding Debt: $" + String.format("%.2f", borrowedAmount));
        System.out.println("Net Value: $" + String.format("%.2f", netValue));
        System.out.println("Total Return: " + String.format("%.2f%%", totalReturn * 100));
        System.out.println("Strategy: Aggressive Momentum & Leverage");
    }
}