package src;

import jade.core.Runtime;
import jade.core.Profile;
import jade.core.ProfileImpl;
import jade.wrapper.AgentController;
import jade.wrapper.ContainerController;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Plateforme de trading multi-agents avec JADE.
 * Lance tous les agents et gère la session de trading.
 */
public class TradingPlatform {
    
    private static final String PLATFORM_ID = "Trading-Platform";
    private static final String HOST = "localhost";
    private static final int PORT = 1099;
    
    // Facteur d'accélération temporelle (60x = 1h simulées en 1 minute réelle)
    public static final int TIME_ACCELERATION_FACTOR = 60;

    // Paramètres du marché
    private static final String STOCK_SYMBOL = "AAPL";
    private static final double INITIAL_STOCK_PRICE = 100.0;
    
    // Durée de session en minutes réelles
    private static final int SESSION_DURATION_MINUTES = 1;

    public static void main(String[] args) {
        ContainerController mainContainer = null;
        try {
            // Propagation du facteur d'accélération aux agents via les propriétés système
            System.setProperty("trading.acceleration", String.valueOf(TIME_ACCELERATION_FACTOR));
            System.setProperty("trading.session.duration", String.valueOf(SESSION_DURATION_MINUTES));

            // Configuration de la plateforme JADE
            Runtime runtime = Runtime.instance();
            Profile profile = new ProfileImpl();
            profile.setParameter(Profile.MAIN_HOST, HOST);
            profile.setParameter(Profile.MAIN_PORT, String.valueOf(PORT));
            profile.setParameter(Profile.GUI, "true");
            profile.setParameter(Profile.PLATFORM_ID, PLATFORM_ID);
            
            System.out.println("Creation du conteneur JADE...");
            mainContainer = runtime.createMainContainer(profile);
            System.out.println("Conteneur JADE cree avec succes!");
            
            // Création du MarketMaker (autorité centrale du marché)
            System.out.println("Creation du MarketMaker...");
            AgentController marketMaker = mainContainer.createNewAgent(
                "MarketMaker",
                "src.agents.MarketMakerAgent",
                new Object[]{STOCK_SYMBOL, INITIAL_STOCK_PRICE}
            );
            marketMaker.start();
            Thread.sleep(1000);

            // Agent graphique pour visualiser le marché en temps réel
            AgentController chart = mainContainer.createNewAgent(
                "MarketChart",
                "src.agents.MarketChartAgent",
                new Object[]{STOCK_SYMBOL, TIME_ACCELERATION_FACTOR * 10}
            );
            chart.start();
            Thread.sleep(1000);
            
            // Trader conservateur (stratégie d'évitement des risques)
            System.out.println("Creation du trader conservateur...");
            AgentController conservativeTrader = mainContainer.createNewAgent(
                "ConservativeTrader-1",
                "src.agents.ConservativeTraderAgent",
                new Object[]{10000.0}
            );
            conservativeTrader.start();
            Thread.sleep(1000);
            
            // Trader agressif (stratégie de prise de risques élevés)
            System.out.println("Creation du trader agressif...");
            AgentController aggressiveTrader = mainContainer.createNewAgent(
                "AggressiveTrader-1",
                "src.agents.AggressiveTraderAgent",
                new Object[]{15000.0}
            );
            aggressiveTrader.start();
            Thread.sleep(1000);
            
            // Traders suiveurs (comportement grégaire basé sur les tendances du marché)
            System.out.println("Creation des traders suiveurs...");
            AgentController followerTrader1 = mainContainer.createNewAgent(
                "FollowerTrader-1",
                "src.agents.FollowerTraderAgent",
                new Object[]{8000.0}
            );
            followerTrader1.start();
            Thread.sleep(1000);
            
            AgentController followerTrader2 = mainContainer.createNewAgent(
                "FollowerTrader-2",
                "src.agents.FollowerTraderAgent",
                new Object[]{8000.0}
            );
            followerTrader2.start();
            Thread.sleep(1000);
            
            // NewsProvider génère les actualités selon le scénario choisi
            System.out.println("Creation du NewsProvider avec scenario: " + args[0]);
            AgentController newsProvider = mainContainer.createNewAgent(
                "NewsProvider",
                "src.agents.NewsProviderAgent",
                new Object[]{args[0], TIME_ACCELERATION_FACTOR}
            );
            newsProvider.start();
            Thread.sleep(1000);
            
            // Agent de statistiques pour analyser le marché final
            AgentController statsAgent = mainContainer.createNewAgent(
                "MarketStats",
                "src.agents.MarketStatsAgent",
                new Object[]{}
            );
            statsAgent.start();
            Thread.sleep(1000);
            
            // Affichage du résumé de la plateforme
            System.out.println("\n==============================================");
            System.out.println("   PLATEFORME DE TRADING MULTI-AGENTS");
            System.out.println("==============================================");
            System.out.println("Plateforme initialisee avec succes!");
            System.out.println("Facteur d'acceleration: " + TIME_ACCELERATION_FACTOR + "x");
            System.out.println("Duree de session: " + SESSION_DURATION_MINUTES + " minutes");
            
            // Calcul de la durée réelle avec accélération temporelle
            long realSessionMs = SESSION_DURATION_MINUTES * 60 * 1000L;
            int realSessionSeconds = (int)(realSessionMs / 1000);
            int simulatedMinutes = SESSION_DURATION_MINUTES * TIME_ACCELERATION_FACTOR;
            
            System.out.println("\n==============================================");
            System.out.println("   PLATEFORME DE TRADING MULTI-AGENTS");
            System.out.println("==============================================");
            System.out.println("Plateforme initialisee avec succes!");
            System.out.println("Facteur d'acceleration: " + TIME_ACCELERATION_FACTOR + "x");
            System.out.println("Duree de session reelle: " + SESSION_DURATION_MINUTES + " minute(s)");
            System.out.println("Duree de session simulee: " + simulatedMinutes + " minutes");
            System.out.println("Scenario: " + scenario);
            System.out.println("MarketMaker: Actif (AAPL @ $100.00)");
            System.out.println("Trader Conservateur: $10,000");
            System.out.println("Trader Agressif: $15,000");
            System.out.println("Traders Suiveurs: 2x $8,000");
            System.out.println("NewsProvider: Actif");
            System.out.println("==============================================");
            System.out.println("Session demarree! Trading en cours...");
            System.out.println("==============================================\n");
            
            // Variables pour le suivi de la session
            long startTime = System.currentTimeMillis();
            int reportCount = 0;
            long reportInterval = 5000; // Rapport toutes les 5 secondes
            
            System.out.println("SESSION DE TRADING ACTIVE - Duree: " + realSessionMs/1000 + " secondes");
            
            // Boucle principale de la session
            while (System.currentTimeMillis() - startTime < realSessionMs) {
                Thread.sleep(reportInterval);
                reportCount++;
                
                long elapsed = System.currentTimeMillis() - startTime;
                long remaining = realSessionMs - elapsed;
                
                // Affichage périodique de l'état de la session
                String timeStamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
                String progressMsg = String.format("[RAPPORT %d] %s - Ecoule: %ds, Restant: %ds", 
                                                reportCount, timeStamp, elapsed/1000, remaining/1000);
                
                System.out.println(progressMsg);
                
                // Vérification de l'intégrité du conteneur
                if (mainContainer == null) {
                    System.err.println("Conteneur principal defaillant!");
                    break;
                }
                
                // Affichage du pourcentage de progression tous les 4 rapports (20 secondes)
                if (reportCount % 4 == 0) {
                    System.out.println("Session " + (elapsed * 100 / realSessionMs) + "% complete...");
                }
            }
            
            System.out.println("\n=== SESSION DE TRADING TERMINEE ===");
            System.out.println("Duree finale: " + (System.currentTimeMillis() - startTime)/1000 + " secondes");
            
            // Temps alloué pour la génération des rapports finaux par les agents
            System.out.println("Finalisation des rapports...");
            Thread.sleep(5000);
            
        } catch (Exception e) {
            System.err.println("Erreur dans la plateforme: " + e.getMessage());
            e.printStackTrace();
        } finally {
            try {
                System.out.println("\nNettoyage...");
                
                if (mainContainer != null) {
                    System.out.println("Arret de la plateforme JADE...");
                    Thread.sleep(2000);
                    mainContainer.kill();
                    System.out.println("Arret de JADE termine");
                }
                
            } catch (Exception e) {
                System.err.println("Erreur lors du nettoyage: " + e.getMessage());
            }
            
            System.out.println("\n=== ARRET DE LA PLATEFORME TERMINE ===");
        }
    }
}