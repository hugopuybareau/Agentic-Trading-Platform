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
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Market Maker Agent - Central market authority
 * Manages order book, price discovery, and trade execution
 */
public class MarketMakerAgent extends Agent {
    
    private String stockSymbol;
    private double currentPrice;
    private double bidPrice;
    private double askPrice;
    private double spread = 0.01; // 1% spread
    private OrderBook orderBook;
    private Map<AID, Portfolio> portfolios;
    private List<Trade> tradeHistory;
    private MarketStatistics stats;
    private int totalVolume = 0;
    private double volatility = 0.02; // 2% base volatility
    
    @Override
    protected void setup() {
        Object[] args = getArguments();
        if (args != null && args.length >= 2) {
            stockSymbol = (String) args[0];
            currentPrice = (Double) args[1];
        } else {
            stockSymbol = "AAPL";
            currentPrice = 100.0;
        }
        
        bidPrice = currentPrice * (1 - spread/2);
        askPrice = currentPrice * (1 + spread/2);
        
        orderBook = new OrderBook();
        portfolios = new ConcurrentHashMap<>();
        tradeHistory = new ArrayList<>();
        stats = new MarketStatistics();
        
        // Register with DF
        registerService();
        
        // Add behaviours
        addBehaviour(new PriceQuotationBehaviour(this, 1000)); // Quote every second
        addBehaviour(new OrderProcessingBehaviour());
        addBehaviour(new MarketMakingBehaviour(this, 2000)); // Adjust spreads
        addBehaviour(new PortfolioManagementBehaviour());
        
        System.out.println("MarketMaker started: " + stockSymbol + " @ $" + currentPrice);
    }
    
    private void registerService() {
        DFAgentDescription dfd = new DFAgentDescription();
        dfd.setName(getAID());
        ServiceDescription sd = new ServiceDescription();
        sd.setType("market-maker");
        sd.setName("stock-exchange");
        dfd.addServices(sd);
        
        try {
            DFService.register(this, dfd);
        } catch (FIPAException e) {
            e.printStackTrace();
        }
    }
    
    /**
     * Behaviour for broadcasting price quotations
     */
    private class PriceQuotationBehaviour extends TickerBehaviour {
        public PriceQuotationBehaviour(Agent a, long period) {
            super(a, period);
        }
        
        @Override
        protected void onTick() {
            // Update prices based on order imbalance
            updatePrices();
            
            // Broadcast market data
            ACLMessage quote = new ACLMessage(ACLMessage.INFORM);
            quote.setProtocol("MARKET-DATA");
            quote.setContent(String.format(
                "PRICE:%s:%.2f:BID:%.2f:ASK:%.2f:VOLUME:%d:VOLATILITY:%.4f",
                stockSymbol, currentPrice, bidPrice, askPrice, totalVolume, volatility
            ));
            
            // Send to all registered traders
            for (AID trader : portfolios.keySet()) {
                quote.addReceiver(trader);
            }
            
            send(quote);
        }
        
        private void updatePrices() {
            // Calculate order imbalance
            int buyOrders = orderBook.getBuyOrdersCount();
            int sellOrders = orderBook.getSellOrdersCount();
            
            double imbalance = 0;
            if (buyOrders + sellOrders > 0) {
                imbalance = (double)(buyOrders - sellOrders) / (buyOrders + sellOrders);
            }
            
            // Adjust price based on imbalance
            double priceChange = currentPrice * volatility * imbalance * 0.1;
            currentPrice += priceChange;
            
            // Add some random walk
            double randomWalk = (Math.random() - 0.5) * currentPrice * volatility * 0.05;
            currentPrice += randomWalk;
            
            // Update bid/ask
            double dynamicSpread = spread * (1 + volatility * 5); // Wider spread in volatile markets
            bidPrice = currentPrice * (1 - dynamicSpread/2);
            askPrice = currentPrice * (1 + dynamicSpread/2);
            
            // Update statistics
            stats.updatePrice(currentPrice);
            volatility = stats.getVolatility();
        }
    }
    
    /**
     * Behaviour for processing trading orders
     */
    private class OrderProcessingBehaviour extends CyclicBehaviour {
        @Override
        public void action() {
            MessageTemplate mt = MessageTemplate.and(
                MessageTemplate.MatchProtocol("TRADING"),
                MessageTemplate.MatchPerformative(ACLMessage.REQUEST)
            );
            
            ACLMessage msg = receive(mt);
            if (msg != null) {
                processOrder(msg);
            } else {
                block();
            }
        }
        
        private void processOrder(ACLMessage msg) {
            String content = msg.getContent();
            AID trader = msg.getSender();
            
            // Initialize portfolio if new trader
            if (!portfolios.containsKey(trader)) {
                portfolios.put(trader, new Portfolio(trader.getLocalName()));
            }
            
            String[] parts = content.split(":");
            String orderType = parts[0]; // BUY or SELL
            String symbol = parts[1];
            int quantity = Integer.parseInt(parts[2]);
            String priceType = parts[4]; // MARKET_PRICE or LIMIT
            
            Order order = new Order(trader, orderType, symbol, quantity, 
                                  "MARKET_PRICE".equals(priceType) ? 
                                  (orderType.equals("BUY") ? askPrice : bidPrice) : 
                                  Double.parseDouble(parts[5]));
            
            // Try to execute order
            boolean executed = executeOrder(order);
            
            // Send confirmation
            ACLMessage reply = msg.createReply();
            if (executed) {
                reply.setPerformative(ACLMessage.CONFIRM);
                reply.setContent("EXECUTED:" + order.toString());
                totalVolume += quantity;
            } else {
                reply.setPerformative(ACLMessage.REFUSE);
                reply.setContent("REJECTED:Insufficient funds or shares");
            }
            send(reply);
        }
        
        private boolean executeOrder(Order order) {
            Portfolio portfolio = portfolios.get(order.getTrader());
            
            if (order.getType().equals("BUY")) {
                double cost = order.getQuantity() * order.getPrice();
                if (portfolio.getCash() >= cost) {
                    portfolio.addCash(-cost);
                    portfolio.addShares(order.getSymbol(), order.getQuantity());
                    
                    Trade trade = new Trade(order.getTrader().getLocalName(), 
                                          "BUY", order.getSymbol(), 
                                          order.getQuantity(), order.getPrice());
                    tradeHistory.add(trade);
                    broadcastTrade(trade);
                    return true;
                }
            } else if (order.getType().equals("SELL")) {
                if (portfolio.getShares(order.getSymbol()) >= order.getQuantity()) {
                    portfolio.removeShares(order.getSymbol(), order.getQuantity());
                    portfolio.addCash(order.getQuantity() * order.getPrice());
                    
                    Trade trade = new Trade(order.getTrader().getLocalName(), 
                                          "SELL", order.getSymbol(), 
                                          order.getQuantity(), order.getPrice());
                    tradeHistory.add(trade);
                    broadcastTrade(trade);
                    return true;
                }
            }
            
            return false;
        }
        
        private void broadcastTrade(Trade trade) {
            ACLMessage tradeMsg = new ACLMessage(ACLMessage.INFORM);
            tradeMsg.setProtocol("TRADE-EXECUTED");
            tradeMsg.setContent(trade.toString());
            
            for (AID trader : portfolios.keySet()) {
                tradeMsg.addReceiver(trader);
            }
            
            send(tradeMsg);
        }
    }
    
    /**
     * Behaviour for market making activities
     */
    private class MarketMakingBehaviour extends TickerBehaviour {
        public MarketMakingBehaviour(Agent a, long period) {
            super(a, period);
        }
        
        @Override
        protected void onTick() {
            // Adjust spread based on volatility and volume
            if (volatility > 0.05) {
                spread = Math.min(0.05, spread * 1.1); // Widen spread in volatile markets
            } else if (volatility < 0.02 && totalVolume > 100) {
                spread = Math.max(0.005, spread * 0.95); // Tighten spread in calm markets
            }
            
            // Provide liquidity by placing orders at bid/ask
            orderBook.addBuyOrder(new Order(getAID(), "BUY", stockSymbol, 10, bidPrice));
            orderBook.addSellOrder(new Order(getAID(), "SELL", stockSymbol, 10, askPrice));
        }
    }
    
    /**
     * Portfolio registration and management
     */
    private class PortfolioManagementBehaviour extends CyclicBehaviour {
        @Override
        public void action() {
            MessageTemplate mt = MessageTemplate.and(
                MessageTemplate.MatchProtocol("PORTFOLIO"),
                MessageTemplate.MatchPerformative(ACLMessage.REQUEST)
            );
            
            ACLMessage msg = receive(mt);
            if (msg != null) {
                String content = msg.getContent();
                AID trader = msg.getSender();
                
                if (content.startsWith("REGISTER:")) {
                    double initialCash = Double.parseDouble(content.split(":")[1]);
                    Portfolio portfolio = new Portfolio(trader.getLocalName());
                    portfolio.addCash(initialCash);
                    portfolios.put(trader, portfolio);
                    
                    ACLMessage reply = msg.createReply();
                    reply.setPerformative(ACLMessage.CONFIRM);
                    reply.setContent("REGISTERED:Success");
                    send(reply);
                    
                    System.out.println("Trader registered: " + trader.getLocalName() + 
                                     " with $" + initialCash);
                }
            } else {
                block();
            }
        }
    }
    
    @Override
    protected void takeDown() {
        try {
            DFService.deregister(this);
        } catch (FIPAException e) {
            e.printStackTrace();
        }
        System.out.println("MarketMaker shutting down. Total volume: " + totalVolume);
    }
}