import jade.core.Runtime;
import jade.core.Profile;
import jade.core.ProfileImpl;
import jade.wrapper.AgentController;
import jade.wrapper.ContainerController;
import jade.wrapper.StaleProxyException;

/**
 * Main class for the Autonomous Financial Trading System
 * Launches the JADE platform and creates all trading agents
 */
public class TradingPlatform {
    
    private static final String PLATFORM_ID = "Trading-Platform";
    private static final String HOST = "localhost";
    private static final int PORT = 1099;
    
    public static void main(String[] args) {
        try {
            // Get JADE runtime
            Runtime runtime = Runtime.instance();
            
            // Create main container with GUI
            Profile profile = new ProfileImpl();
            profile.setParameter(Profile.MAIN_HOST, HOST);
            profile.setParameter(Profile.MAIN_PORT, String.valueOf(PORT));
            profile.setParameter(Profile.GUI, "true");
            
            ContainerController mainContainer = runtime.createMainContainer(profile);
            
            // Create Market Maker Agent (central market)
            AgentController marketMaker = mainContainer.createNewAgent(
                "MarketMaker",
                "agents.MarketMakerAgent",
                new Object[]{"AAPL", 100.0} // Initial stock and price
            );
            marketMaker.start();
            
            // Wait for market to initialize
            Thread.sleep(1000);
            
            // Create Conservative Trader
            AgentController conservativeTrader = mainContainer.createNewAgent(
                "ConservativeTrader-1",
                "agents.ConservativeTraderAgent",
                new Object[]{10000.0} // Initial capital
            );
            conservativeTrader.start();
            
            // Create Aggressive Trader
            AgentController aggressiveTrader = mainContainer.createNewAgent(
                "AggressiveTrader-1",
                "agents.AggressiveTraderAgent",
                new Object[]{15000.0} // Initial capital
            );
            aggressiveTrader.start();
            
            // Create Follower Trader
            AgentController followerTrader = mainContainer.createNewAgent(
                "FollowerTrader-1",
                "agents.FollowerTraderAgent",
                new Object[]{8000.0} // Initial capital
            );
            followerTrader.start();
            
            // Create another Follower
            AgentController followerTrader2 = mainContainer.createNewAgent(
                "FollowerTrader-2",
                "agents.FollowerTraderAgent",
                new Object[]{8000.0}
            );
            followerTrader2.start();
            
            // Create News Provider
            AgentController newsProvider = mainContainer.createNewAgent(
                "NewsProvider",
                "agents.NewsProviderAgent",
                null
            );
            newsProvider.start();
            
            // Create Market Statistics Agent (optional - for monitoring)
            AgentController statsAgent = mainContainer.createNewAgent(
                "MarketStats",
                "agents.MarketStatsAgent",
                null
            );
            statsAgent.start();
            
            System.out.println("==============================================");
            System.out.println("   AUTONOMOUS FINANCIAL TRADING SYSTEM");
            System.out.println("==============================================");
            System.out.println("Platform initialized successfully!");
            System.out.println("Market Maker: Active");
            System.out.println("Traders: 4 agents created");
            System.out.println("News Provider: Active");
            System.out.println("Market Statistics: Active");
            System.out.println("==============================================");
            
        } catch (Exception e) {
            System.err.println("Error starting trading platform: " + e.getMessage());
            e.printStackTrace();
        }
    }
}