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
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Agent Market Maker - Autorite centrale du marche.
 * Gere le carnet d'ordres, la decouverte des prix et l'execution des transactions.
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
    private double volatility = 0.02; // 2% volatilite de base
    private final List<AID> subscribers = new ArrayList<>();
    
    @Override
    protected void setup() {
        // Initialisation des parametres avec acceleration temporelle
        Object[] args = getArguments();
        int accelerationFactor = getAccelerationFactor();
        
        if (args != null && args.length >= 2) {
            stockSymbol = (String) args[0];
            currentPrice = (Double) args[1];
        } else {
            stockSymbol = "AAPL";
            currentPrice = 100.0;
        }
        
        // Initialisation des prix
        bidPrice = currentPrice * (1 - spread/2);
        askPrice = currentPrice * (1 + spread/2);
        
        // Initialisation des structures de donnees
        orderBook = new OrderBook();
        portfolios = new ConcurrentHashMap<>();
        registeredTraders = new ArrayList<>();
        tradeHistory = new ArrayList<>();
        stats = new MarketStatistics();
        
        // Calcul des intervalles avec acceleration
        long baseQuoteInterval = 3000;  // 3 secondes de base
        long baseMakingInterval = 5000; // 5 secondes de base
        
        long quoteInterval = adjustInterval(baseQuoteInterval);
        long makingInterval = adjustInterval(baseMakingInterval);
                
        registerService();
        
        addBehaviour(new PortfolioManagementBehaviour());
        addBehaviour(new OrderProcessingBehaviour());
        addBehaviour(new NewsImpactBehaviour());
        
        // Demarrage des comportements periodiques avec delai initial
        addBehaviour(new WakerBehaviour(this, 1000) {
            @Override
            protected void onWake() {
                addBehaviour(new PriceQuotationBehaviour(myAgent, quoteInterval));
                addBehaviour(new MarketMakingBehaviour(myAgent, makingInterval));
                
                System.out.println("MarketMaker behaviours started");
                broadcastMarketData();
            }
        });

        addBehaviour(new MarketDataSubscriptionBehaviour());
        addBehaviour(new MarketDataHeartbeat(this, 1000));

        // Envoi d'un snapshot initial pour remplir le graphique
        addBehaviour(new OneShotBehaviour() {
            @Override
            public void action() {
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

    // Diffusion des donnees de marche a tous les participants
    private void broadcastMarketData() {
        String marketData = String.format(
            "PRICE:%s:%.2f:BID:%.2f:ASK:%.2f:VOLUME:%d:VOLATILITY:%.4f",
            stockSymbol, currentPrice, bidPrice, askPrice, totalVolume, volatility
        );

        ACLMessage quote = new ACLMessage(ACLMessage.INFORM);
        quote.setProtocol("MARKET-DATA");
        quote.setContent(marketData);

        // Envoi aux traders et abonnes (sans doublons)
        Set<AID> receivers = new LinkedHashSet<>();
        if (registeredTraders != null) receivers.addAll(registeredTraders);
        if (subscribers != null) receivers.addAll(subscribers);

        // Notification systematique de MarketStats
        receivers.add(new AID("MarketStats", AID.ISLOCALNAME));

        if (receivers.isEmpty()) {
            System.out.println("broadcastMarketData: no receivers yet");
            return;
        }

        for (AID r : receivers) {
            quote.addReceiver(r);
        }

        System.out.println("Broadcasting MARKET-DATA to " + receivers.size() + " receivers: " + marketData);
        send(quote);
    }

    // Gestion des abonnements aux donnees de marche
    private class MarketDataSubscriptionBehaviour extends CyclicBehaviour {
        @Override
        public void action() {
            MessageTemplate mt = MessageTemplate.MatchProtocol("MARKET-DATA-SUB");
            ACLMessage msg = receive(mt);
            if (msg != null) {
                if (msg.getPerformative() == ACLMessage.SUBSCRIBE) {
                    AID who = msg.getSender();
                    if (subscribers.stream().noneMatch(a -> a.getLocalName().equals(who.getLocalName()))) {
                        subscribers.add(who);
                        System.out.println(getLocalName() + " + subscriber: " + who.getLocalName());
                    }
                    ACLMessage ack = msg.createReply();
                    ack.setPerformative(ACLMessage.CONFIRM);
                    ack.setProtocol("MARKET-DATA-SUB");
                    ack.setContent("OK");
                    send(ack);

                    // Envoi immediat d'un snapshot
                    broadcastMarketData();
                }
            } else {
                block();
            }
        }
    }

    // Diffusion periodique des donnees de marche
    private class MarketDataHeartbeat extends TickerBehaviour {
        public MarketDataHeartbeat(Agent a, long period) { super(a, period); }

        @Override
        protected void onTick() {
            broadcastMarketData();
        }
    }
    
    /**
     * Comportement de diffusion des cotations de prix.
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
            // Calcul du desequilibre des ordres
            int buyOrders = orderBook.getBuyOrdersCount();
            int sellOrders = orderBook.getSellOrdersCount();
            
            double imbalance = 0;
            if (buyOrders + sellOrders > 0) {
                imbalance = (double)(buyOrders - sellOrders) / (buyOrders + sellOrders);
            }
            
            // Ajustement du prix selon le desequilibre
            double priceChange = currentPrice * volatility * imbalance * 0.1;
            currentPrice += priceChange;
            
            // Ajout d'une marche aleatoire
            double randomWalk = (Math.random() - 0.5) * currentPrice * volatility * 0.05;
            currentPrice += randomWalk;
            
            // Mise a jour bid/ask
            double dynamicSpread = spread * (1 + volatility * 5);
            bidPrice = currentPrice * (1 - dynamicSpread/2);
            askPrice = currentPrice * (1 + dynamicSpread/2);
            
            // Mise a jour des statistiques
            stats.updatePrice(currentPrice);
            volatility = stats.getVolatility();
        }
    }

    // Traitement de l'impact des actualites sur le marche
    private class NewsImpactBehaviour extends CyclicBehaviour {
        @Override
        public void action() {
            MessageTemplate mt = MessageTemplate.MatchProtocol("NEWS");
            ACLMessage msg = receive(mt);
            
            if (msg != null) {
                String newsContent = msg.getContent();
                processNewsImpact(newsContent);
            } else {
                block();
            }
        }
        
        private void processNewsImpact(String newsContent) {
            try {
                String[] parts = newsContent.split(":");
                
                if (parts.length >= 4) {
                    String sentiment = parts[0];
                    String impactLevel = parts[3];
                    
                    double priceImpact = 0;
                    
                    if ("HIGH".equals(impactLevel)) {
                        priceImpact = "POSITIVE".equals(sentiment) ? 0.015 : -0.015;
                    } else if ("MEDIUM".equals(impactLevel)) {
                        priceImpact = "POSITIVE".equals(sentiment) ? 0.008 : -0.008;
                    } else if ("LOW".equals(impactLevel)) {
                        priceImpact = "POSITIVE".equals(sentiment) ? 0.003 : -0.003;
                    }
                    
                    currentPrice *= (1 + priceImpact);
                    bidPrice = currentPrice * 0.999;
                    askPrice = currentPrice * 1.001;
                    
                    System.out.println("MarketMaker: News impact " + sentiment + " " + impactLevel + 
                                    " -> Price " + String.format("%+.2f%%", priceImpact * 100) + 
                                    " -> $" + String.format("%.2f", currentPrice));
                    
                    broadcastMarketData();
                }
            } catch (Exception e) {
                System.err.println("MarketMaker error processing news: " + e.getMessage());
            }
        }
    }
    
    /**
     * Traitement des ordres de trading.
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
                
                String action = parts[0];
                String symbol = parts[1];
                String quantityStr = parts[2];
                String priceStr = parts[3];
                
                priceStr = priceStr.replace(",", ".");
                
                int quantity = Integer.parseInt(quantityStr);
                double orderPrice = Double.parseDouble(priceStr);
                
                System.out.println("Parsed order: " + action + " " + quantity + " " + symbol + " @ $" + orderPrice);
                
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
                
                double executionPrice = askPrice;
                double totalCost = quantity * executionPrice;
                
                if (portfolio.getCash() < totalCost) {
                    System.err.println("Insufficient funds for " + trader.getLocalName() + 
                                     ". Required: $" + String.format("%.2f", totalCost) + 
                                     ", Available: $" + String.format("%.2f", portfolio.getCash()));
                    sendOrderResponse(trader, "REJECTED:Insufficient funds");
                    return;
                }
                
                // Execution de la transaction
                portfolio.removeCash(totalCost);
                portfolio.addShares(symbol, quantity);
                
                // Mise a jour des statistiques
                totalVolume += quantity;
                Trade trade = new Trade(trader.getLocalName(), "BUY", symbol, quantity, executionPrice);
                tradeHistory.add(trade);
                
                // Impact sur le prix
                double priceImpact = quantity * 0.001;
                currentPrice += priceImpact;
                bidPrice = currentPrice * (1 - spread/2);
                askPrice = currentPrice * (1 + spread/2);
                
                sendOrderResponse(trader, "EXECUTED:BUY:" + quantity + ":" + symbol + ":" + executionPrice);
                
                System.out.println("BUY ORDER EXECUTED: " + trader.getLocalName() + 
                                 " bought " + quantity + " " + symbol + " @ $" + String.format("%.2f", executionPrice) +
                                 " (Total: $" + String.format("%.2f", totalCost) + ")");
                System.out.println("   New price: $" + String.format("%.2f", currentPrice) + 
                                 ", Portfolio cash remaining: $" + String.format("%.2f", portfolio.getCash()));
                
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
                
                double executionPrice = bidPrice;
                double totalValue = quantity * executionPrice;
                
                // Execution de la transaction
                portfolio.removeShares(symbol, quantity);
                portfolio.addCash(totalValue);
                
                // Mise a jour des statistiques
                totalVolume += quantity;
                Trade trade = new Trade(trader.getLocalName(), "SELL", symbol, quantity, executionPrice);
                tradeHistory.add(trade);
                
                // Impact sur le prix (negatif pour les ventes)
                double priceImpact = quantity * 0.001;
                currentPrice -= priceImpact;
                bidPrice = currentPrice * (1 - spread/2);
                askPrice = currentPrice * (1 + spread/2);
                
                sendOrderResponse(trader, "EXECUTED:SELL:" + quantity + ":" + symbol + ":" + executionPrice);
                
                System.out.println("SELL ORDER EXECUTED: " + trader.getLocalName() + 
                                 " sold " + quantity + " " + symbol + " @ $" + String.format("%.2f", executionPrice) +
                                 " (Total: $" + String.format("%.2f", totalValue) + ")");
                System.out.println("   New price: $" + String.format("%.2f", currentPrice) + 
                                 ", Portfolio cash: $" + String.format("%.2f", portfolio.getCash()));
                
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
            
            // Envoi aux traders
            for (AID trader : registeredTraders) {
                tradeMsg.addReceiver(trader);
            }
            
            // Envoi a MarketStatsAgent
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
     * Comportement de tenue de marche (market making).
     */
    private class MarketMakingBehaviour extends TickerBehaviour {
        public MarketMakingBehaviour(Agent a, long period) {
            super(a, period);
        }
        
        @Override
        protected void onTick() {
            // Ajustement du spread selon volatilite et volume
            if (volatility > 0.05) {
                spread = Math.min(0.05, spread * 1.1); // Elargissement en cas de volatilite
            } else if (volatility < 0.02 && totalVolume > 100) {
                spread = Math.max(0.005, spread * 0.95); // Resserrement en marche calme
            }
            
            // Mise a jour des prix bid/ask
            bidPrice = currentPrice * (1 - spread/2);
            askPrice = currentPrice * (1 + spread/2);
            
            // Provision de liquidite
            if (Math.random() < 0.1) {
                System.out.println("MarketMaker providing liquidity at bid: $" + 
                                 String.format("%.2f", bidPrice) + 
                                 ", ask: $" + String.format("%.2f", askPrice));
            }
        }
    }
    
    /**
     * Gestion des portfolios et enregistrement des traders.
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
                    
                    // Envoi immediat des donnees de marche au nouveau trader
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
        
        System.out.println("\n=== MARKETMAKER FINAL REPORT ===");
        System.out.println("Total Volume: " + totalVolume + " shares");
        System.out.println("Total Trades: " + tradeHistory.size());
        System.out.println("Final Price: $" + String.format("%.2f", currentPrice));
        System.out.println("Registered Traders: " + registeredTraders.size());
        System.out.println("=====================================");
        
        System.out.println("MarketMaker shutting down. Total volume: " + totalVolume);
    }
}