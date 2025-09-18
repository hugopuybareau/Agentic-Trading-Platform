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
    private double spread = 0.001; // 0.1% spread
    private OrderBook orderBook;
    private Map<AID, Portfolio> portfolios;
    private List<AID> registeredTraders;
    private List<Trade> tradeHistory;
    private MarketStatistics stats;
    private int totalVolume = 0;
    private double volatility = 0.02; // 2% base volatility
    

    @Override
    protected void setup() {
        // 🔧 1. Initialiser les paramètres avec l'accélération
        Object[] args = getArguments();
        int accelerationFactor = getAccelerationFactor();
        
        if (args != null && args.length >= 2) {
            stockSymbol = (String) args[0];
            currentPrice = (Double) args[1];
        } else {
            stockSymbol = "AAPL";
            currentPrice = 100.0;
        }
        
        // 🔧 4. Initialiser les prix
        bidPrice = currentPrice * (1 - spread/2);
        askPrice = currentPrice * (1 + spread/2);
        
        // 🔧 5. Initialiser les structures de données
        orderBook = new OrderBook();
        portfolios = new ConcurrentHashMap<>();
        registeredTraders = new ArrayList<>();
        tradeHistory = new ArrayList<>();
        stats = new MarketStatistics();
        
        // 🔧 7. Calculer les intervals avec accélération
        long baseQuoteInterval = 3000;  // 3 secondes de base
        long baseMakingInterval = 5000; // 5 secondes de base
        
        long quoteInterval = adjustInterval(baseQuoteInterval);
        long makingInterval = adjustInterval(baseMakingInterval);
                
        registerService();
        
        addBehaviour(new PortfolioManagementBehaviour()); // Immédiat
        addBehaviour(new OrderProcessingBehaviour());     // Immédiat
        
        // 🔧 10. Démarrer les behaviours périodiques après un délai
        addBehaviour(new WakerBehaviour(this, 1000) {
            @Override
            protected void onWake() {
                // Ajouter les behaviours périodiques
                addBehaviour(new PriceQuotationBehaviour(myAgent, quoteInterval));
                addBehaviour(new MarketMakingBehaviour(myAgent, makingInterval));
                
                System.out.println("MarketMaker behaviours started");
                
                // 🔧 11. Premier broadcast immédiat
                broadcastMarketData();
            }
        });
        
        System.out.println("MarketMaker setup complete - Ready for traders!");
    }

    private int getAccelerationFactor() {
        return Integer.parseInt(System.getProperty("trading.acceleration", "1"));
    }

    private long adjustInterval(long originalInterval) {
        return originalInterval / getAccelerationFactor();
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

    // Méthode pour diffuser les données de marché
    private void broadcastMarketData() {
        String marketData = String.format(
            "PRICE:%s:%.2f:BID:%.2f:ASK:%.2f:VOLUME:%d:VOLATILITY:%.4f",
            stockSymbol, currentPrice, bidPrice, askPrice, totalVolume, volatility
        );
        
        System.out.println("Broadcasting: " + marketData);
        
        ACLMessage quote = new ACLMessage(ACLMessage.INFORM);
        quote.setProtocol("MARKET-DATA");
        quote.setContent(marketData);
        
        if (registeredTraders.isEmpty()) {
            System.out.println("No traders registered yet");
        } else {
            for (AID trader : registeredTraders) {
                quote.addReceiver(trader);
                System.out.println("Sending to: " + trader.getLocalName());
            }
            send(quote);
            System.out.println("Market data sent to " + registeredTraders.size() + " traders");
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
            updatePrices();
            broadcastMarketData();
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
            double dynamicSpread = spread * (1 + volatility * 5);
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
                String orderContent = msg.getContent();
                AID trader = msg.getSender();
                
                System.out.println("MarketMaker received order: " + orderContent + " from " + trader.getLocalName());
                
                processOrder(trader, orderContent);
            } else {
                block();
            }
        }
        
        private void processOrder(AID trader, String orderStr) {
            try {
                System.out.println("Processing order: " + orderStr);
                
                String[] parts = orderStr.split(":");
                System.out.println("Order parts: " + Arrays.toString(parts));
                
                if (parts.length < 4) {
                    System.err.println("Invalid order format. Expected 4 parts, got " + parts.length);
                    sendOrderResponse(trader, "REJECTED:Invalid format");
                    return;
                }
                
                String action = parts[0];      // BUY ou SELL
                String symbol = parts[1];      // AAPL
                String quantityStr = parts[2]; // 29
                String priceStr = parts[3];    // 100,03
                
                // Remplacer les virgules par des points pour le parsing
                priceStr = priceStr.replace(",", ".");
                
                int quantity = Integer.parseInt(quantityStr);
                double orderPrice = Double.parseDouble(priceStr);
                
                System.out.println("Parsed order: " + action + " " + quantity + " " + symbol + " @ $" + orderPrice);
                
                // Traiter l'ordre
                if ("BUY".equals(action)) {
                    executeBuyOrder(trader, symbol, quantity, orderPrice);
                } else if ("SELL".equals(action)) {
                    executeSellOrder(trader, symbol, quantity, orderPrice);
                } else {
                    System.err.println("Unknown order action: " + action);
                    sendOrderResponse(trader, "REJECTED:Unknown action");
                }
                
            } catch (NumberFormatException e) {
                System.err.println("Error parsing order numbers: " + e.getMessage());
                sendOrderResponse(trader, "REJECTED:Invalid number format");
            } catch (Exception e) {
                System.err.println("Error processing order: " + e.getMessage());
                e.printStackTrace();
                sendOrderResponse(trader, "REJECTED:Processing error");
            }
        }
        
        private void executeBuyOrder(AID trader, String symbol, int quantity, double orderPrice) {
            try {
                Portfolio portfolio = portfolios.get(trader);
                if (portfolio == null) {
                    System.err.println("No portfolio found for trader: " + trader.getLocalName());
                    sendOrderResponse(trader, "REJECTED:No portfolio");
                    return;
                }
                
                // Utiliser le prix de marché actuel (askPrice pour les achats)
                double executionPrice = askPrice;
                double totalCost = quantity * executionPrice;
                
                if (portfolio.getCash() < totalCost) {
                    System.err.println("Insufficient funds for " + trader.getLocalName() + 
                                     ". Required: $" + String.format("%.2f", totalCost) + 
                                     ", Available: $" + String.format("%.2f", portfolio.getCash()));
                    sendOrderResponse(trader, "REJECTED:Insufficient funds");
                    return;
                }
                
                // Exécuter la transaction
                portfolio.removeCash(totalCost);
                portfolio.addShares(symbol, quantity);
                
                // Mettre à jour les statistiques
                totalVolume += quantity;
                Trade trade = new Trade(trader.getLocalName(), "BUY", symbol, quantity, executionPrice);
                tradeHistory.add(trade);
                
                // Impact sur le prix
                double priceImpact = quantity * 0.001; // 0.1% par action
                currentPrice += priceImpact;
                bidPrice = currentPrice * (1 - spread/2);
                askPrice = currentPrice * (1 + spread/2);
                
                // Confirmer l'ordre
                sendOrderResponse(trader, "EXECUTED:BUY:" + quantity + ":" + symbol + ":" + executionPrice);
                
                System.out.println("✅ BUY ORDER EXECUTED: " + trader.getLocalName() + 
                                 " bought " + quantity + " " + symbol + " @ $" + String.format("%.2f", executionPrice) +
                                 " (Total: $" + String.format("%.2f", totalCost) + ")");
                System.out.println("   New price: $" + String.format("%.2f", currentPrice) + 
                                 ", Portfolio cash remaining: $" + String.format("%.2f", portfolio.getCash()));
                
                // Diffuser l'information du trade
                broadcastTradeExecution(trade);
                
            } catch (Exception e) {
                System.err.println("Error executing buy order: " + e.getMessage());
                e.printStackTrace();
                sendOrderResponse(trader, "REJECTED:Execution error");
            }
        }
        
        private void executeSellOrder(AID trader, String symbol, int quantity, double orderPrice) {
            try {
                Portfolio portfolio = portfolios.get(trader);
                if (portfolio == null) {
                    sendOrderResponse(trader, "REJECTED:No portfolio");
                    return;
                }
                
                if (portfolio.getShares(symbol) < quantity) {
                    System.err.println("Insufficient shares for " + trader.getLocalName() + 
                                     ". Required: " + quantity + 
                                     ", Available: " + portfolio.getShares(symbol));
                    sendOrderResponse(trader, "REJECTED:Insufficient shares");
                    return;
                }
                
                // Utiliser le prix de marché actuel (bidPrice pour les ventes)
                double executionPrice = bidPrice;
                double totalValue = quantity * executionPrice;
                
                // Exécuter la transaction
                portfolio.removeShares(symbol, quantity);
                portfolio.addCash(totalValue);
                
                // Mettre à jour les statistiques
                totalVolume += quantity;
                Trade trade = new Trade(trader.getLocalName(), "SELL", symbol, quantity, executionPrice);
                tradeHistory.add(trade);
                
                // Impact sur le prix (négatif pour les ventes)
                double priceImpact = quantity * 0.001;
                currentPrice -= priceImpact;
                bidPrice = currentPrice * (1 - spread/2);
                askPrice = currentPrice * (1 + spread/2);
                
                // Confirmer l'ordre
                sendOrderResponse(trader, "EXECUTED:SELL:" + quantity + ":" + symbol + ":" + executionPrice);
                
                System.out.println("✅ SELL ORDER EXECUTED: " + trader.getLocalName() + 
                                 " sold " + quantity + " " + symbol + " @ $" + String.format("%.2f", executionPrice) +
                                 " (Total: $" + String.format("%.2f", totalValue) + ")");
                System.out.println("   New price: $" + String.format("%.2f", currentPrice) + 
                                 ", Portfolio cash: $" + String.format("%.2f", portfolio.getCash()));
                
                // Diffuser l'information du trade
                broadcastTradeExecution(trade);
                
            } catch (Exception e) {
                System.err.println("Error executing sell order: " + e.getMessage());
                e.printStackTrace();
                sendOrderResponse(trader, "REJECTED:Execution error");
            }
        }
        
        private void sendOrderResponse(AID trader, String response) {
            ACLMessage reply = new ACLMessage(ACLMessage.CONFIRM);
            reply.setProtocol("TRADING");
            reply.setContent(response);
            reply.addReceiver(trader);
            send(reply);
            
            System.out.println("Response sent to " + trader.getLocalName() + ": " + response);
        }
        
    private void broadcastTradeExecution(Trade trade) {
        String tradeInfo = String.format("TRADE:%s:%s:%d:%.2f:%s",
            trade.getTraderName(), trade.getSymbol(), trade.getQuantity(), 
            trade.getPrice(), trade.getAction());
        
        ACLMessage tradeMsg = new ACLMessage(ACLMessage.INFORM);
        tradeMsg.setProtocol("TRADE-EXECUTED");
        tradeMsg.setContent(tradeInfo);
        
        // Envoyer aux traders
        for (AID trader : registeredTraders) {
            tradeMsg.addReceiver(trader);
        }
        
        // 🔧 AJOUT: Envoyer aussi à MarketStatsAgent
        try {
            DFAgentDescription template = new DFAgentDescription();
            ServiceDescription sd = new ServiceDescription();
            sd.setType("market-stats");
            template.addServices(sd);
            
            DFAgentDescription[] result = DFService.search(myAgent, template);
            for (DFAgentDescription agent : result) {
                tradeMsg.addReceiver(agent.getName());
            }
            
            if (result.length > 0) {
                System.out.println("Trade also sent to " + result.length + " MarketStats agent(s)");
            }
        } catch (FIPAException e) {
            System.err.println("Error finding MarketStats agent: " + e.getMessage());
        }
        
        send(tradeMsg);
        System.out.println("Trade broadcasted to all traders: " + tradeInfo);
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
            
            // Update bid/ask prices
            bidPrice = currentPrice * (1 - spread/2);
            askPrice = currentPrice * (1 + spread/2);
            
            // Provide liquidity (simplified)
            if (Math.random() < 0.1) { // 10% chance to add liquidity
                System.out.println("MarketMaker providing liquidity at bid: $" + 
                                 String.format("%.2f", bidPrice) + 
                                 ", ask: $" + String.format("%.2f", askPrice));
            }
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
                    
                    // Ajouter le trader à la liste des traders enregistrés
                    if (!registeredTraders.contains(trader)) {
                        registeredTraders.add(trader);
                        System.out.println("Trader added to broadcast list: " + trader.getLocalName());
                    }
                    
                    ACLMessage reply = msg.createReply();
                    reply.setPerformative(ACLMessage.CONFIRM);
                    reply.setContent("REGISTERED:Success");
                    send(reply);
                    
                    System.out.println("Trader registered: " + trader.getLocalName() + 
                                     " with $" + String.format("%.2f", initialCash));
                    
                    // Envoyer immédiatement les données de marché au nouveau trader
                    ACLMessage welcome = new ACLMessage(ACLMessage.INFORM);
                    welcome.setProtocol("MARKET-DATA");
                    welcome.setContent(String.format(
                        "PRICE:%s:%.2f:BID:%.2f:ASK:%.2f:VOLUME:%d:VOLATILITY:%.4f",
                        stockSymbol, currentPrice, bidPrice, askPrice, totalVolume, volatility
                    ));
                    welcome.addReceiver(trader);
                    send(welcome);
                    System.out.println("Welcome market data sent to: " + trader.getLocalName());
                }
                
                // Gérer les requêtes de statut de portfolio
                else if (content.equals("STATUS")) {
                    Portfolio portfolio = portfolios.get(trader);
                    if (portfolio != null) {
                        ACLMessage reply = msg.createReply();
                        reply.setPerformative(ACLMessage.INFORM);
                        reply.setContent("CASH:" + portfolio.getCash() + 
                                       ":SHARES:" + portfolio.getShares(stockSymbol));
                        send(reply);
                    }
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
        
        // Rapport final
        System.out.println("\n=== MARKETMAKER FINAL REPORT ===");
        System.out.println("Total Volume: " + totalVolume + " shares");
        System.out.println("Total Trades: " + tradeHistory.size());
        System.out.println("Final Price: $" + String.format("%.2f", currentPrice));
        System.out.println("Registered Traders: " + registeredTraders.size());
        System.out.println("=====================================");
        
        System.out.println("MarketMaker shutting down. Total volume: " + totalVolume);
    }
}