package src;

import jade.core.Runtime;
import jade.core.Profile;
import jade.core.ProfileImpl;
import jade.wrapper.AgentController;
import jade.wrapper.ContainerController;
import jade.wrapper.StaleProxyException;
import java.io.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Main class for the Autonomous Financial Trading System
 * Launches the JADE platform and creates all trading agents
 */
public class TradingPlatform {
    
    private static final String PLATFORM_ID = "Trading-Platform";
    private static final String HOST = "localhost";
    private static final int PORT = 1099;
    
    // FACTEUR D'ACCÉLÉRATION - Changez cette valeur pour modifier la vitesse
    public static final int TIME_ACCELERATION_FACTOR = 60; // 10x plus rapide pour voir les résultats
    
    // Session duration in minutes (real time)
    private static final int SESSION_DURATION_MINUTES = 1; // 2 minutes de session
    
    public static void main(String[] args) {
        ContainerController mainContainer = null;
        try {
                    
            System.setProperty("trading.acceleration", String.valueOf(TIME_ACCELERATION_FACTOR));
            
            // Get JADE runtime
            Runtime runtime = Runtime.instance();
            
            // Create main container with GUI
            Profile profile = new ProfileImpl();
            profile.setParameter(Profile.MAIN_HOST, HOST);
            profile.setParameter(Profile.MAIN_PORT, String.valueOf(PORT));
            profile.setParameter(Profile.GUI, "true");
            profile.setParameter(Profile.PLATFORM_ID, PLATFORM_ID);
            
            System.out.println("Creating JADE main container...");
            mainContainer = runtime.createMainContainer(profile);
            System.out.println("JADE container created successfully!");
            
            // 🔧 CORRECTION 2: Create Market Maker with acceleration parameter
            System.out.println("Creating MarketMaker with " + TIME_ACCELERATION_FACTOR + "x acceleration...");
            AgentController marketMaker = mainContainer.createNewAgent(
                "MarketMaker",
                "src.agents.MarketMakerAgent",
                new Object[]{"AAPL", 100.0} // 🔧 Passer l'accélération
            );
            marketMaker.start();
            System.out.println("MarketMaker started!");
            
            Thread.sleep(1000);
            
            // Create Conservative Trader
            System.out.println("Creating ConservativeTrader-1...");
            AgentController conservativeTrader = mainContainer.createNewAgent(
                "ConservativeTrader-1",
                "src.agents.ConservativeTraderAgent",
                new Object[]{10000.0}
            );
            conservativeTrader.start();
            Thread.sleep(1000); // 🔧 Plus de délai
            
            // Create Aggressive Trader
            System.out.println("Creating AggressiveTrader-1...");
            AgentController aggressiveTrader = mainContainer.createNewAgent(
                "AggressiveTrader-1",
                "src.agents.AggressiveTraderAgent",
                new Object[]{15000.0}
            );
            aggressiveTrader.start();
            Thread.sleep(1000); // 🔧 Plus de délai
            
            // Create Follower Traders
            System.out.println("Creating FollowerTrader-1...");
            AgentController followerTrader1 = mainContainer.createNewAgent(
                "FollowerTrader-1",
                "src.agents.FollowerTraderAgent",
                new Object[]{8000.0}
            );
            followerTrader1.start();
            Thread.sleep(1000);
            
            System.out.println("Creating FollowerTrader-2...");
            AgentController followerTrader2 = mainContainer.createNewAgent(
                "FollowerTrader-2",
                "src.agents.FollowerTraderAgent",
                new Object[]{8000.0}
            );
            followerTrader2.start();
            Thread.sleep(1000);
            
            // Create News Provider with acceleration
            System.out.println("Creating NewsProvider...");
            AgentController newsProvider = mainContainer.createNewAgent(
                "NewsProvider",
                "src.agents.NewsProviderAgent",
                new Object[]{TIME_ACCELERATION_FACTOR} // 🔧 Passer l'accélération
            );
            newsProvider.start();
            Thread.sleep(1000);
            
            // Create Market Statistics Agent
            System.out.println("Creating MarketStats...");
            AgentController statsAgent = mainContainer.createNewAgent(
                "MarketStats",
                "src.agents.MarketStatsAgent",
                new Object[]{}
            );
            statsAgent.start();
            Thread.sleep(1000);
            
            // 🔧 CORRECTION 4: Display correct acceleration
            System.out.println("\n==============================================");
            System.out.println("   AUTONOMOUS FINANCIAL TRADING SYSTEM");
            System.out.println("==============================================");
            System.out.println("Platform initialized successfully!");
            System.out.println("Time Acceleration Factor: " + TIME_ACCELERATION_FACTOR + "x"); // 🔧 CORRECTION
            System.out.println("Session Duration: " + SESSION_DURATION_MINUTES + " minutes");
            
            // 🔧 CORRECTION 5: Calculate real session duration
            long realSessionMs = (SESSION_DURATION_MINUTES * 60 * 1000L) / TIME_ACCELERATION_FACTOR;
            System.out.println("Real Session Duration: " + realSessionMs/1000 + " seconds");
            
            System.out.println("Market Maker: Active (AAPL @ $100.00)");
            System.out.println("Conservative Trader: $10,000 capital");
            System.out.println("Aggressive Trader: $15,000 capital");
            System.out.println("Follower Traders: 2x $8,000 capital");
            System.out.println("News Provider: Active");
            System.out.println("Market Statistics: Active");
            System.out.println("==============================================");
            System.out.println("Session started! Trading in progress...");
            System.out.println("==============================================\n");
            
            long startTime = System.currentTimeMillis();
            int reportCount = 0;
            long reportInterval = 5000; // Report every 5 seconds
            
            System.out.println("🚀 TRADING SESSION ACTIVE - Duration: " + realSessionMs/1000 + " seconds");
            
            while (System.currentTimeMillis() - startTime < realSessionMs) {
                Thread.sleep(reportInterval);
                reportCount++;
                
                long elapsed = System.currentTimeMillis() - startTime;
                long remaining = realSessionMs - elapsed;
                
                String timeStamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
                String progressMsg = String.format("[REPORT %d] %s - Elapsed: %ds, Remaining: %ds", 
                                                reportCount, timeStamp, elapsed/1000, remaining/1000);
                
                System.out.println(progressMsg);
                
                // 🔧 Health check
                if (mainContainer == null) {
                    System.err.println("❌ Main container died!");
                    break;
                }
                
                // 🔧 Progress indicator
                if (reportCount % 4 == 0) { // Every 20 seconds
                    System.out.println("📊 Session " + (elapsed * 100 / realSessionMs) + "% complete...");
                }
            }
            
            System.out.println("\n🎯 === TRADING SESSION COMPLETE ===");
            System.out.println("Final session duration: " + (System.currentTimeMillis() - startTime)/1000 + " seconds");
            
            // 🔧 CORRECTION 7: Give agents time to finish reporting
            System.out.println("⏳ Allowing agents to complete final reports...");
            Thread.sleep(5000);
            
        } catch (Exception e) {
            System.err.println("❌ Error in trading platform: " + e.getMessage());
            e.printStackTrace();
        } finally {
            try {
                System.out.println("\n🧹 Cleaning up...");
                
                if (mainContainer != null) {
                    System.out.println("🔴 Shutting down JADE platform...");
                    Thread.sleep(2000); // Give final messages time to process
                    mainContainer.kill();
                    System.out.println("✅ JADE platform shutdown complete");
                }
                
            } catch (Exception e) {
                System.err.println("❌ Error during cleanup: " + e.getMessage());
            }
            
            System.out.println("\n🎉 === TRADING PLATFORM SHUTDOWN COMPLETE ===");
        }
    }
}