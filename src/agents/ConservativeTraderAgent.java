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
 * Conservative Trader Agent - BDI Architecture
 * Low risk strategy focusing on long-term value investing
 * Target: 5-10% annual returns with minimal risk
 */
public class ConservativeTraderAgent extends Agent {
    
    // BDI Components
    private TradingBeliefs beliefs;
    private ConservativeDesires desires;
    private TradingIntentions intentions;
    
    // Portfolio Management
    private double initialCapital;
    private double currentCash;
    private int sharesOwned;
    private double averageBuyPrice;
    
    // Risk Management
    private final double MAX_POSITION_SIZE = 0.3; // Max 30% of capital in one trade
    private final double STOP_LOSS = 0.05; // 5% stop loss
    private final double TAKE_PROFIT = 0.10; // 10% profit target
    
    // Trading Parameters
    private int tradingCooldown = 0;
    private final int MIN_TRADE_INTERVAL = 5; // Minimum ticks between trades
    
    private AID marketMaker;
    
    @Override
    protected void setup() {
        // Initialize capital
        Object[] args = getArguments();
        if (args != null && args.length > 0) {
            initialCapital = (Double) args[0];
        } else {
            initialCapital = 10000.0;
        }
        currentCash = initialCapital;
        sharesOwned = 0;
        
        // Initialize BDI components
        beliefs = new TradingBeliefs();
        desires = new ConservativeDesires();
        intentions = new TradingIntentions();
        
        System.out.println("Conservative Trader " + getLocalName() + 
                         " started with capital: $" + initialCapital);
        
        // Find market maker
        addBehaviour(new WakerBehaviour(this, 1000) {
            @Override
            protected void onWake() {
                findMarketMaker();
                
                // Register with market
                if (marketMaker != null) {
                    registerWithMarket();
                    
                    // Add trading behaviours
                    addBehaviour(new MarketDataReceiver());
                    addBehaviour(new ConservativeTradingBehaviour(myAgent, 3000));
                    addBehaviour(new RiskManagementBehaviour(myAgent, 5000));
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
     * Conservative Desires - Investment goals
     */
    private class ConservativeDesires {
        
        public boolean wantsToBuy() {
            // Buy when market is oversold and fundamentals are good
            return beliefs.isOversold() && 
                   !beliefs.hasNegativeNews() &&
                   beliefs.getVolatility() < 0.03 && // Low volatility preferred
                   getCurrentPositionPercent() < 0.7; // Not over-invested
        }
        
        public boolean wantsToSell() {
            if (sharesOwned == 0) return false;
            
            // Sell when target reached or stop loss triggered
            double currentReturn = (beliefs.getCurrentPrice() - averageBuyPrice) / averageBuyPrice;
            
            return currentReturn >= TAKE_PROFIT || // Take profit
                   currentReturn <= -STOP_LOSS || // Stop loss
                   (beliefs.isOverbought() && currentReturn > 0.05); // Partial profit in overbought market
        }
        
        public boolean wantsToHold() {
            // Hold during normal market conditions
            return !wantsToBuy() && !wantsToSell();
        }
        
        private double getCurrentPositionPercent() {
            double totalValue = currentCash + (sharesOwned * beliefs.getCurrentPrice());
            return (sharesOwned * beliefs.getCurrentPrice()) / totalValue;
        }
    }
    
    /**
     * Trading Intentions - Concrete action plans
     */
    private class TradingIntentions {
        
        public int calculateBuyQuantity() {
            // Conservative position sizing
            double maxInvestment = currentCash * MAX_POSITION_SIZE;
            double priceWithSlippage = beliefs.getAskPrice() * 1.01; // Account for slippage
            
            int quantity = (int)(maxInvestment / priceWithSlippage);
            
            // Ensure minimum meaningful position
            if (quantity < 10) {
                quantity = Math.min(10, (int)(currentCash / priceWithSlippage));
            }
            
            return quantity;
        }
        
        public int calculateSellQuantity() {
            // Sell in portions to average out
            double currentReturn = (beliefs.getCurrentPrice() - averageBuyPrice) / averageBuyPrice;
            
            if (currentReturn <= -STOP_LOSS) {
                // Emergency exit - sell all
                return sharesOwned;
            } else if (currentReturn >= TAKE_PROFIT) {
                // Take profit - sell 50%
                return sharesOwned / 2;
            } else {
                // Normal sell - sell 30%
                return Math.max(10, sharesOwned / 3);
            }
        }
        
        public boolean shouldExecuteTrade() {
            // Check cooldown and market conditions
            return tradingCooldown == 0 && 
                   beliefs.getVolume() > 50 && // Sufficient liquidity
                   beliefs.getSpread() < 0.02; // Reasonable spread
        }
    }
    
    /**
     * Behaviour for receiving and processing market data
     */
    private class MarketDataReceiver extends CyclicBehaviour {
        @Override
        public void action() {
            MessageTemplate mt = MessageTemplate.MatchProtocol("MARKET-DATA");
            ACLMessage msg = receive(mt);
            
            if (msg != null) {
                beliefs.updateMarketData(msg.getContent());
                
                // Also listen for trade executions
                MessageTemplate tradeMt = MessageTemplate.MatchProtocol("TRADE-EXECUTED");
                ACLMessage tradeMsg = receive(tradeMt);
                if (tradeMsg != null) {
                    beliefs.updateTradeInfo(tradeMsg.getContent());
                }
                
                // Listen for news
                MessageTemplate newsMt = MessageTemplate.MatchProtocol("NEWS");
                ACLMessage newsMsg = receive(newsMt);
                if (newsMsg != null) {
                    beliefs.updateNews(newsMsg.getContent());
                }
            } else {
                block();
            }
        }
    }
    
    /**
     * Main trading behaviour - Conservative strategy
     */
    private class ConservativeTradingBehaviour extends TickerBehaviour {
        public ConservativeTradingBehaviour(Agent a, long period) {
            super(a, period);
        }
        
        @Override
        protected void onTick() {
            // Update cooldown
            if (tradingCooldown > 0) {
                tradingCooldown--;
            }
            
            // Check desires and execute intentions
            if (desires.wantsToBuy() && intentions.shouldExecuteTrade()) {
                executeBuyOrder();
            } else if (desires.wantsToSell() && intentions.shouldExecuteTrade()) {
                executeSellOrder();
            } else if (desires.wantsToHold()) {
                // Log holding decision
                if (Math.random() < 0.1) { // Log occasionally
                    System.out.println(getLocalName() + " holding position. " +
                                     "Price: $" + String.format("%.2f", beliefs.getCurrentPrice()) +
                                     ", Sentiment: " + beliefs.getMarketSentiment());
                }
            }
        }
        
        private void executeBuyOrder() {
            int quantity = intentions.calculateBuyQuantity();
            if (quantity > 0) {
                ACLMessage order = new ACLMessage(ACLMessage.REQUEST);
                order.addReceiver(marketMaker);
                order.setProtocol("TRADING");
                order.setContent("BUY:AAPL:" + quantity + ":SHARES:MARKET_PRICE");
                send(order);
                
                // Update portfolio tracking
                double price = beliefs.getAskPrice();
                double totalCost = averageBuyPrice * sharesOwned + price * quantity;
                sharesOwned += quantity;
                if (sharesOwned > 0) {
                    averageBuyPrice = totalCost / sharesOwned;
                }
                currentCash -= price * quantity;
                
                tradingCooldown = MIN_TRADE_INTERVAL;
                
                System.out.println(getLocalName() + " BUYING " + quantity + 
                                 " shares @ $" + String.format("%.2f", price) +
                                 " (Oversold signal, RSI: " + String.format("%.1f", beliefs.getRSI()) + ")");
            }
        }
        
        private void executeSellOrder() {
            int quantity = intentions.calculateSellQuantity();
            quantity = Math.min(quantity, sharesOwned);
            
            if (quantity > 0) {
                ACLMessage order = new ACLMessage(ACLMessage.REQUEST);
                order.addReceiver(marketMaker);
                order.setProtocol("TRADING");
                order.setContent("SELL:AAPL:" + quantity + ":SHARES:MARKET_PRICE");
                send(order);
                
                // Update portfolio tracking
                double price = beliefs.getBidPrice();
                sharesOwned -= quantity;
                currentCash += price * quantity;
                
                double profit = (price - averageBuyPrice) * quantity;
                
                tradingCooldown = MIN_TRADE_INTERVAL;
                
                System.out.println(getLocalName() + " SELLING " + quantity + 
                                 " shares @ $" + String.format("%.2f", price) +
                                 " (Profit: $" + String.format("%.2f", profit) + ")");
            }
        }
    }
    
    /**
     * Risk management behaviour
     */
    private class RiskManagementBehaviour extends TickerBehaviour {
        public RiskManagementBehaviour(Agent a, long period) {
            super(a, period);
        }
        
        @Override
        protected void onTick() {
            // Calculate current portfolio value
            double portfolioValue = currentCash + (sharesOwned * beliefs.getCurrentPrice());
            double totalReturn = (portfolioValue - initialCapital) / initialCapital;
            
            // Log performance
            System.out.println(getLocalName() + " Portfolio Status: " +
                             "Value: $" + String.format("%.2f", portfolioValue) +
                             ", Return: " + String.format("%.2f%%", totalReturn * 100) +
                             ", Shares: " + sharesOwned +
                             ", Cash: $" + String.format("%.2f", currentCash));
            
            // Emergency risk check
            if (totalReturn < -0.15) { // 15% portfolio loss
                // Liquidate all positions
                if (sharesOwned > 0) {
                    System.out.println(getLocalName() + " EMERGENCY LIQUIDATION!");
                    ACLMessage order = new ACLMessage(ACLMessage.REQUEST);
                    order.addReceiver(marketMaker);
                    order.setProtocol("TRADING");
                    order.setContent("SELL:AAPL:" + sharesOwned + ":SHARES:MARKET_PRICE");
                    send(order);
                    
                    currentCash += sharesOwned * beliefs.getBidPrice();
                    sharesOwned = 0;
                }
            }
        }
    }
    
    @Override
    protected void takeDown() {
        double finalValue = currentCash + (sharesOwned * beliefs.getCurrentPrice());
        double totalReturn = (finalValue - initialCapital) / initialCapital;
        
        System.out.println("=== " + getLocalName() + " Final Report ===");
        System.out.println("Initial Capital: $" + initialCapital);
        System.out.println("Final Value: $" + String.format("%.2f", finalValue));
        System.out.println("Total Return: " + String.format("%.2f%%", totalReturn * 100));
        System.out.println("Strategy: Conservative Value Investing");
    }
}