package src.agents;

import jade.core.Agent;
import jade.core.AID;
import jade.core.behaviours.*;
import jade.lang.acl.ACLMessage;
import jade.domain.DFService;
import jade.domain.FIPAAgentManagement.*;
import jade.domain.FIPAException;
import java.util.*;

/**
 * Agent Fournisseur d'Actualites
 * Genere des evenements de marche et des actualites qui affectent les decisions de trading
 * Adapte automatiquement son comportement selon le facteur d'acceleration et la duree de simulation
 */
public class NewsProviderAgent extends Agent {
    
    // Configuration temporelle
    private int accelerationFactor;
    private int sessionDurationMinutes;
    private int simulatedDurationMinutes;
    private long sessionStartTime;
    private long sessionEndTime;
    
    // Gestion des abonnes et generation aleatoire
    private List<AID> subscribers;
    private Random random;
    
    // Configuration du scenario
    private ScenarioType currentScenario;
    private Map<ScenarioType, ScenarioConfig> scenarioConfigs;
    private Map<MarketPhase, List<NewsTemplate>> newsTemplates;
    private MarketPhase currentPhase = MarketPhase.CALM;
    
    /**
     * Phases du marche basees sur des ratios de progression
     */
    private enum MarketPhase {
        CALM(0.0, 0.166),
        EMERGING_TREND(0.166, 0.333),
        BUBBLE(0.333, 0.583),
        CRASH(0.583, 0.75),
        STABILIZATION(0.75, 1.0);
        
        private final double startRatio;
        private final double endRatio;
        
        MarketPhase(double start, double end) {
            this.startRatio = start;
            this.endRatio = end;
        }
        
        boolean isActive(double progressRatio) {
            return progressRatio >= startRatio && progressRatio < endRatio;
        }
    }
    
    /**
     * Types de scenarios disponibles
     */
    public enum ScenarioType {
        MARKET_CYCLE,
        STABLE_MARKET,
        BULL_MARKET,
        BEAR_MARKET,
        VOLATILE_MARKET,
        NEWS_DRIVEN,
        PANIC_SCENARIO
    }
    
    /**
     * Configuration d'un scenario
     */
    private class ScenarioConfig {
        int minIntervalSeconds;
        int maxIntervalSeconds;
        double volatilityLevel;
        double newsFrequency;
        List<String> mainSentiments;
        
        ScenarioConfig(int minInterval, int maxInterval, double volatility, 
                      double frequency, String... sentiments) {
            this.minIntervalSeconds = minInterval;
            this.maxIntervalSeconds = maxInterval;
            this.volatilityLevel = volatility;
            this.newsFrequency = frequency;
            this.mainSentiments = Arrays.asList(sentiments);
        }
    }
    
    /**
     * Template d'actualite
     */
    private class NewsTemplate {
        String sentiment;
        String description;
        String impact;
        
        NewsTemplate(String sentiment, String description, String impact) {
            this.sentiment = sentiment;
            this.description = description;
            this.impact = impact;
        }
        
        String toNewsString() {
            return sentiment + ":" + description + ":IMPACT:" + impact;
        }
    }
    
    @Override
    protected void setup() {
        sessionStartTime = System.currentTimeMillis();
        subscribers = new ArrayList<>();
        random = new Random();
        
        // Lecture des parametres depuis les proprietes systeme
        accelerationFactor = getAccelerationFactor();
        sessionDurationMinutes = Integer.parseInt(
            System.getProperty("trading.session.duration", "1"));
        
        // Calcul de la duree simulee
        simulatedDurationMinutes = sessionDurationMinutes * accelerationFactor;
        sessionEndTime = sessionStartTime + (sessionDurationMinutes * 60 * 1000L);
        
        // Recuperation du scenario
        Object[] args = getArguments();
        ScenarioType selectedScenario = ScenarioType.MARKET_CYCLE;
        
        if (args != null && args.length > 0) {
            try {
                selectedScenario = ScenarioType.valueOf(args[0].toString().toUpperCase());
                System.out.println("Scenario selectionne: " + selectedScenario);
            } catch (Exception e) {
                System.out.println("Scenario invalide, utilisation de MARKET_CYCLE par defaut");
            }
        }
        
        currentScenario = selectedScenario;
        
        initializeNewsTemplates();
        initializeScenarioSettings(selectedScenario);
        
        System.out.println("\n=== NewsProvider Configuration ===");
        System.out.println("Facteur d'acceleration: " + accelerationFactor + "x");
        System.out.println("Duree reelle: " + sessionDurationMinutes + " minute(s)");
        System.out.println("Duree simulee: " + simulatedDurationMinutes + " minutes");
        System.out.println("Scenario: " + currentScenario);
        System.out.println("==================================\n");
        
        registerService();
        
        addBehaviour(new WakerBehaviour(this, 2000) {
            @Override
            protected void onWake() {
                findSubscribers();
                subscribers.add(new AID("MarketChart", AID.ISLOCALNAME));
                System.out.println("NewsProvider: " + subscribers.size() + " abonnes trouves");
                startScenarioBehaviour();
            }
        });
    }
    
    /**
     * Recupere le facteur d'acceleration depuis les proprietes systeme
     */
    private int getAccelerationFactor() {
        return Integer.parseInt(System.getProperty("trading.acceleration", "1"));
    }
    
    /**
     * Ajuste un intervalle en temps reel selon le facteur d'acceleration
     */
    private long adjustInterval(long originalInterval) {
        return originalInterval / accelerationFactor;
    }
    
    /**
     * Calcule le ratio de progression de la session (0.0 a 1.0)
     */
    private double getSessionProgress() {
        long elapsed = System.currentTimeMillis() - sessionStartTime;
        long totalDuration = sessionEndTime - sessionStartTime;
        return Math.min(1.0, (double) elapsed / totalDuration);
    }
    
    /**
     * Calcule les minutes simulees ecoulees
     */
    private int getSimulatedMinutesElapsed() {
        double progress = getSessionProgress();
        return (int) (progress * simulatedDurationMinutes);
    }
    
    /**
     * Verifie si la session est terminee
     */
    private boolean isSessionEnded() {
        return System.currentTimeMillis() >= sessionEndTime;
    }
    
    /**
     * Genere un delai aleatoire en temps reel
     */
    private long getRandomNewsDelay() {
        ScenarioConfig config = scenarioConfigs.get(currentScenario);
        int minSimulatedSeconds = config.minIntervalSeconds;
        int maxSimulatedSeconds = config.maxIntervalSeconds;
        
        int minSimulatedMs = minSimulatedSeconds * 1000;
        int maxSimulatedMs = maxSimulatedSeconds * 1000;
        
        int randomSimulatedMs = minSimulatedMs + random.nextInt(maxSimulatedMs - minSimulatedMs + 1);
        
        return adjustInterval(randomSimulatedMs);
    }
    
    /**
     * Verifie si une actualite doit etre publiee selon la frequence configuree
     */
    private boolean shouldPublishNews() {
        if (isSessionEnded()) return false;
        
        ScenarioConfig config = scenarioConfigs.get(currentScenario);
        return random.nextDouble() < config.newsFrequency;
    }
    
    /**
     * Initialise les parametres pour chaque type de scenario
     */
    private void initializeScenarioSettings(ScenarioType scenario) {
        scenarioConfigs = new HashMap<>();
        
        scenarioConfigs.put(ScenarioType.MARKET_CYCLE, 
            new ScenarioConfig(30, 90, 0.5, 0.7, "NEUTRAL", "POSITIVE", "NEGATIVE"));
        
        scenarioConfigs.put(ScenarioType.STABLE_MARKET,
            new ScenarioConfig(60, 150, 0.1, 0.5, "NEUTRAL", "POSITIVE"));
        
        scenarioConfigs.put(ScenarioType.BULL_MARKET,
            new ScenarioConfig(30, 80, 0.2, 0.8, "POSITIVE", "NEUTRAL"));
        
        scenarioConfigs.put(ScenarioType.BEAR_MARKET,
            new ScenarioConfig(30, 80, 0.3, 0.8, "NEGATIVE", "NEUTRAL"));
        
        scenarioConfigs.put(ScenarioType.VOLATILE_MARKET,
            new ScenarioConfig(15, 45, 0.8, 0.9, "POSITIVE", "NEGATIVE", "NEUTRAL"));
        
        scenarioConfigs.put(ScenarioType.NEWS_DRIVEN,
            new ScenarioConfig(45, 120, 0.6, 0.85, "POSITIVE", "NEGATIVE"));
        
        scenarioConfigs.put(ScenarioType.PANIC_SCENARIO,
            new ScenarioConfig(20, 60, 0.9, 0.95, "NEGATIVE", "NEUTRAL"));
    }
    
    /**
     * Demarre le comportement approprie selon le scenario
     */
    private void startScenarioBehaviour() {
        ScenarioConfig config = scenarioConfigs.get(currentScenario);
        
        switch (currentScenario) {
            case MARKET_CYCLE:
                addBehaviour(new PhaseControlBehaviour(this, adjustInterval(3000)));
                addBehaviour(new RandomNewsGenerationBehaviour(this));
                addBehaviour(new SpecialEventBehaviour(this, adjustInterval(10000)));
                break;
                
            case STABLE_MARKET:
                addBehaviour(new RandomStableMarketBehaviour(this));
                break;
                
            case BULL_MARKET:
                addBehaviour(new RandomBullMarketBehaviour(this));
                break;
                
            case BEAR_MARKET:
                addBehaviour(new RandomBearMarketBehaviour(this));
                break;
                
            case VOLATILE_MARKET:
                addBehaviour(new RandomVolatileMarketBehaviour(this));
                break;
                
            case NEWS_DRIVEN:
                addBehaviour(new RandomNewsDriverBehaviour(this));
                break;
                
            case PANIC_SCENARIO:
                addBehaviour(new RandomPanicScenarioBehaviour(this));
                break;
        }
        
        System.out.println("Scenario " + currentScenario + " demarre");
        System.out.println("Intervalle actualites simule: " + config.minIntervalSeconds + 
                        "-" + config.maxIntervalSeconds + " secondes");
        System.out.println("Intervalle reel: " + 
                        (config.minIntervalSeconds / accelerationFactor) + "-" + 
                        (config.maxIntervalSeconds / accelerationFactor) + " secondes");
    }
    
    /**
     * Generateur d'actualites avec timing aleatoire pour MARKET_CYCLE
     */
    private class RandomNewsGenerationBehaviour extends Behaviour {
        private long nextNewsTime;
        
        public RandomNewsGenerationBehaviour(Agent a) {
            super(a);
            scheduleNextNews();
        }
        
        private void scheduleNextNews() {
            nextNewsTime = System.currentTimeMillis() + getRandomNewsDelay();
        }
        
        @Override
        public void action() {
            if (isSessionEnded()) {
                return;
            }
            
            if (System.currentTimeMillis() >= nextNewsTime) {
                List<NewsTemplate> phaseNews = newsTemplates.get(currentPhase);
                
                if (phaseNews != null && !phaseNews.isEmpty() && shouldPublishNews()) {
                    NewsTemplate news = phaseNews.get(random.nextInt(phaseNews.size()));
                    String newsContent = news.toNewsString();
                    
                    broadcastNews(newsContent);
                    
                    int simMinutes = getSimulatedMinutesElapsed();
                    System.out.println(String.format("[T+%dmin] %s: %s [%s/%s]", 
                        simMinutes, currentPhase, news.description, 
                        news.sentiment, news.impact));
                }
                
                scheduleNextNews();
            } else {
                block(100);
            }
        }
        
        @Override
        public boolean done() {
            return isSessionEnded();
        }
    }
    
    /**
     * Comportement de controle des phases base sur le ratio de progression
     */
    private class PhaseControlBehaviour extends TickerBehaviour {
        public PhaseControlBehaviour(Agent a, long period) {
            super(a, period);
        }
        
        @Override
        protected void onTick() {
            if (isSessionEnded()) {
                stop();
                return;
            }
            
            double progress = getSessionProgress();
            MarketPhase newPhase = currentPhase;
            
            for (MarketPhase phase : MarketPhase.values()) {
                if (phase.isActive(progress)) {
                    newPhase = phase;
                    break;
                }
            }
            
            if (newPhase != currentPhase) {
                currentPhase = newPhase;
                int simMinutes = getSimulatedMinutesElapsed();
                System.out.println(String.format("\n=== [T+%dmin] CHANGEMENT DE PHASE: %s (%.1f%%) ===\n", 
                    simMinutes, currentPhase, progress * 100));
                broadcastPhaseChange();
            }
        }
        
        private void broadcastPhaseChange() {
            String announcement = "";
            
            switch (currentPhase) {
                case EMERGING_TREND:
                    announcement = "POSITIVE:L'elan du marche s'accelere dans le secteur tech:IMPACT:HIGH";
                    break;
                case BUBBLE:
                    announcement = "POSITIVE:L'achat euphorique atteint des sommets:IMPACT:HIGH";
                    break;
                case CRASH:
                    announcement = "NEGATIVE:ALERTE - La bulle eclate:IMPACT:HIGH";
                    break;
                case STABILIZATION:
                    announcement = "NEUTRAL:Les marches retrouvent leur equilibre apres la volatilite:IMPACT:MEDIUM";
                    break;
                default:
                    return;
            }
            
            broadcastNews(announcement);
        }
    }
    
    /**
     * Evenements speciaux temporises selon la progression
     */
    private class SpecialEventBehaviour extends TickerBehaviour {
        private boolean earningsReleased = false;
        private boolean ceoStatementMade = false;
        private boolean analystDowngrade = false;
        
        public SpecialEventBehaviour(Agent a, long period) {
            super(a, period);
        }
        
        @Override
        protected void onTick() {
            if (isSessionEnded()) {
                stop();
                return;
            }
            
            double progress = getSessionProgress();
            int simMinutes = getSimulatedMinutesElapsed();
            
            if (progress >= 0.25 && !earningsReleased && currentPhase == MarketPhase.EMERGING_TREND) {
                broadcastNews("POSITIVE:FLASH - AAPL depasse les benefices de 20%:IMPACT:HIGH");
                System.out.println(String.format("*** [T+%dmin] EVENEMENT SPECIAL: Depassement des benefices ***", simMinutes));
                earningsReleased = true;
            }
            
            if (progress >= 0.50 && !ceoStatementMade && currentPhase == MarketPhase.BUBBLE) {
                broadcastNews("NEUTRAL:Le PDG d'AAPL met en garde contre la survalorisation:IMPACT:HIGH");
                System.out.println(String.format("*** [T+%dmin] EVENEMENT SPECIAL: Avertissement du PDG ***", simMinutes));
                ceoStatementMade = true;
            }
            
            if (progress >= 0.63 && !analystDowngrade && currentPhase == MarketPhase.CRASH) {
                broadcastNews("NEGATIVE:Une grande banque d'investissement degrade AAPL a vendre:IMPACT:HIGH");
                System.out.println(String.format("*** [T+%dmin] EVENEMENT SPECIAL: Degradation d'analyste ***", simMinutes));
                analystDowngrade = true;
            }
            
            if (random.nextDouble() < 0.02) {
                generateShockEvent();
            }
        }
        
        private void generateShockEvent() {
            String[] shockEvents = {
                "NEGATIVE:Flash crash detecte dans le trading d'AAPL:IMPACT:HIGH",
                "POSITIVE:Annonce surprise d'un dividende par AAPL:IMPACT:MEDIUM",
                "NEGATIVE:Trading suspendu en raison d'une activite inhabituelle:IMPACT:HIGH",
                "POSITIVE:Un concurrent majeur fait face a des problemes de production:IMPACT:MEDIUM"
            };
            
            String shock = shockEvents[random.nextInt(shockEvents.length)];
            broadcastNews(shock);
            int simMinutes = getSimulatedMinutesElapsed();
            System.out.println(String.format("[T+%dmin] EVENEMENT DE CHOC", simMinutes));
        }
    }
    
    /**
     * Comportement aleatoire pour marche stable
     */
    private class RandomStableMarketBehaviour extends Behaviour {
        private int newsCount = 0;
        private long nextNewsTime;
        private final int maxNews;
        
        public RandomStableMarketBehaviour(Agent a) {
            super(a);
            maxNews = Math.max(5, simulatedDurationMinutes / 3);
            scheduleNextNews();
            System.out.println("Marche stable: max " + maxNews + " actualites sur " + simulatedDurationMinutes + " min simulees");
        }
        
        private void scheduleNextNews() {
            nextNewsTime = System.currentTimeMillis() + getRandomNewsDelay();
        }
        
        @Override
        public void action() {
            if (isSessionEnded() || newsCount >= maxNews) {
                return;
            }
            
            if (System.currentTimeMillis() >= nextNewsTime && newsCount < maxNews) {
                String[] stableNews = {
                    "NEUTRAL:AAPL maintient une fourchette de trading stable:IMPACT:LOW",
                    "POSITIVE:Croissance moderee des fondamentaux du secteur tech:IMPACT:LOW", 
                    "NEUTRAL:La politique de la Reserve Federale reste inchangee:IMPACT:MEDIUM",
                    "POSITIVE:Les resultats trimestriels d'AAPL repondent aux attentes:IMPACT:LOW",
                    "NEUTRAL:Les analystes maintiennent leurs notes actuelles:IMPACT:LOW"
                };
                
                if (shouldPublishNews()) {
                    String news = stableNews[random.nextInt(stableNews.length)];
                    broadcastNews(news);
                    newsCount++;
                    int simMin = getSimulatedMinutesElapsed();
                    System.out.println(String.format("[T+%dmin] MARCHE STABLE (%d/%d): %s", 
                        simMin, newsCount, maxNews, news.split(":")[1]));
                }
                
                scheduleNextNews();
            } else {
                block(100);
            }
        }
        
        @Override
        public boolean done() {
            return isSessionEnded() || newsCount >= maxNews;
        }
    }
    
    /**
     * Comportement aleatoire pour marche haussier
     */
    private class RandomBullMarketBehaviour extends Behaviour {
        private int newsCount = 0;
        private long nextNewsTime;
        private final int maxNews;
        
        public RandomBullMarketBehaviour(Agent a) {
            super(a);
            maxNews = Math.max(10, simulatedDurationMinutes / 2);
            scheduleNextNews();
            System.out.println("Marche haussier: max " + maxNews + " actualites sur " + simulatedDurationMinutes + " min simulees");
        }
        
        private void scheduleNextNews() {
            nextNewsTime = System.currentTimeMillis() + getRandomNewsDelay();
        }
        
        @Override
        public void action() {
            if (isSessionEnded() || newsCount >= maxNews) {
                return;
            }
            
            if (System.currentTimeMillis() >= nextNewsTime && newsCount < maxNews) {
                String[] bullNews = {
                    "POSITIVE:AAPL atteint un nouveau sommet sur 52 semaines grace a une forte demande:IMPACT:HIGH",
                    "POSITIVE:Le secteur tech mene le rallye du marche:IMPACT:MEDIUM",
                    "POSITIVE:Des investisseurs institutionnels augmentent leurs positions AAPL:IMPACT:HIGH",
                    "POSITIVE:La confiance des consommateurs dans les produits tech s'envole:IMPACT:MEDIUM",
                    "POSITIVE:AAPL annonce une percee d'innovation majeure:IMPACT:HIGH",
                    "NEUTRAL:Quelques prises de benefices observees mais la tendance reste forte:IMPACT:LOW"
                };
                
                if (shouldPublishNews()) {
                    String news;
                    if (random.nextDouble() < 0.8) {
                        news = bullNews[random.nextInt(5)];
                    } else {
                        news = bullNews[5];
                    }
                    
                    broadcastNews(news);
                    newsCount++;
                    int simMin = getSimulatedMinutesElapsed();
                    System.out.println(String.format("[T+%dmin] MARCHE HAUSSIER (%d/%d): %s", 
                        simMin, newsCount, maxNews, news.split(":")[1]));
                }
                
                scheduleNextNews();
            } else {
                block(100);
            }
        }
        
        @Override
        public boolean done() {
            return isSessionEnded() || newsCount >= maxNews;
        }
    }
    
    /**
     * Comportement aleatoire pour marche baissier
     */
    private class RandomBearMarketBehaviour extends Behaviour {
        private int newsCount = 0;
        private long nextNewsTime;
        private final int maxNews;
        
        public RandomBearMarketBehaviour(Agent a) {
            super(a);
            maxNews = Math.max(10, simulatedDurationMinutes / 2);
            scheduleNextNews();
            System.out.println("Marche baissier: max " + maxNews + " actualites sur " + simulatedDurationMinutes + " min simulees");
        }
        
        private void scheduleNextNews() {
            nextNewsTime = System.currentTimeMillis() + getRandomNewsDelay();
        }
        
        @Override
        public void action() {
            if (isSessionEnded() || newsCount >= maxNews) {
                return;
            }
            
            if (System.currentTimeMillis() >= nextNewsTime && newsCount < maxNews) {
                String[] bearNews = {
                    "NEGATIVE:AAPL fait face a des vents contraires dus a l'incertitude economique:IMPACT:HIGH",
                    "NEGATIVE:Le secteur tech sous pression des inquietudes sur les taux:IMPACT:MEDIUM",
                    "NEGATIVE:Des investisseurs majeurs reduisent leur exposition a AAPL:IMPACT:HIGH",
                    "NEGATIVE:Les depenses de consommation en produits tech diminuent:IMPACT:MEDIUM",
                    "NEGATIVE:Des perturbations de la chaine d'approvisionnement affectent AAPL:IMPACT:HIGH",
                    "NEUTRAL:Quelques chasseurs de bonnes affaires emergent mais les ventes persistent:IMPACT:LOW"
                };
                
                if (shouldPublishNews()) {
                    String news;
                    if (random.nextDouble() < 0.8) {
                        news = bearNews[random.nextInt(5)];
                    } else {
                        news = bearNews[5];
                    }
                    
                    broadcastNews(news);
                    newsCount++;
                    int simMin = getSimulatedMinutesElapsed();
                    System.out.println(String.format("[T+%dmin] MARCHE BAISSIER (%d/%d): %s", 
                        simMin, newsCount, maxNews, news.split(":")[1]));
                }
                
                scheduleNextNews();
            } else {
                block(100);
            }
        }
        
        @Override
        public boolean done() {
            return isSessionEnded() || newsCount >= maxNews;
        }
    }
    
    /**
     * Comportement aleatoire pour marche volatil
     */
    private class RandomVolatileMarketBehaviour extends Behaviour {
        private int newsCount = 0;
        private long nextNewsTime;
        private final int maxNews;
        
        public RandomVolatileMarketBehaviour(Agent a) {
            super(a);
            maxNews = Math.max(15, simulatedDurationMinutes);
            scheduleNextNews();
            System.out.println("Marche volatil: max " + maxNews + " actualites sur " + simulatedDurationMinutes + " min simulees");
        }
        
        private void scheduleNextNews() {
            nextNewsTime = System.currentTimeMillis() + getRandomNewsDelay();
        }
        
        @Override
        public void action() {
            if (isSessionEnded() || newsCount >= maxNews) {
                return;
            }
            
            if (System.currentTimeMillis() >= nextNewsTime && newsCount < maxNews) {
                String[] volatileNews = {
                    "POSITIVE:AAPL bondit sur des rumeurs de benefices:IMPACT:HIGH",
                    "NEGATIVE:Des inquietudes emergent sur les previsions d'AAPL:IMPACT:HIGH",
                    "POSITIVE:Un analyste releve AAPL sur l'innovation:IMPACT:MEDIUM",
                    "NEGATIVE:Le controle reglementaire s'intensifie pour la tech:IMPACT:HIGH",
                    "NEUTRAL:Des signaux mixtes deroulent les participants du marche:IMPACT:MEDIUM"
                };
                
                if (shouldPublishNews()) {
                    String news = volatileNews[random.nextInt(volatileNews.length)];
                    broadcastNews(news);
                    newsCount++;
                    int simMin = getSimulatedMinutesElapsed();
                    System.out.println(String.format("[T+%dmin] MARCHE VOLATIL (%d/%d): %s", 
                        simMin, newsCount, maxNews, news.split(":")[1]));
                }
                
                scheduleNextNews();
            } else {
                block(100);
            }
        }
        
        @Override
        public boolean done() {
            return isSessionEnded() || newsCount >= maxNews;
        }
    }
    
    /**
     * Comportement aleatoire pour scenario pilote par actualites
     */
    private class RandomNewsDriverBehaviour extends Behaviour {
        private int newsCount = 0;
        private long nextNewsTime;
        private final int maxNews;
        
        public RandomNewsDriverBehaviour(Agent a) {
            super(a);
            maxNews = Math.max(8, simulatedDurationMinutes / 2);
            scheduleNextNews();
            System.out.println("Scenario NEWS-DRIVEN: max " + maxNews + " actualites sur " + simulatedDurationMinutes + " min simulees");
        }
        
        private void scheduleNextNews() {
            nextNewsTime = System.currentTimeMillis() + getRandomNewsDelay();
        }
        
        @Override
        public void action() {
            if (isSessionEnded() || newsCount >= maxNews) {
                return;
            }
            
            if (System.currentTimeMillis() >= nextNewsTime && newsCount < maxNews) {
                String[] majorEvents = {
                    "POSITIVE:FLASH - AAPL annonce un produit revolutionnaire:IMPACT:HIGH",
                    "NEGATIVE:URGENT - Une violation de securite majeure affecte AAPL:IMPACT:HIGH",
                    "POSITIVE:Les benefices d'AAPL depassent toutes les attentes:IMPACT:HIGH",
                    "NEGATIVE:Une enquete federale vise les pratiques d'AAPL:IMPACT:HIGH",
                    "POSITIVE:AAPL remporte un contrat gouvernemental majeur:IMPACT:MEDIUM",
                    "NEGATIVE:Un fournisseur cle d'AAPL fait face a un arret de production:IMPACT:HIGH",
                    "POSITIVE:L'annonce d'une division d'actions d'AAPL surprend les marches:IMPACT:MEDIUM",
                    "NEGATIVE:Des degradations d'analystes declenchent des ventes d'AAPL:IMPACT:HIGH"
                };
                
                if (shouldPublishNews()) {
                    String news = majorEvents[random.nextInt(majorEvents.length)];
                    broadcastNews(news);
                    newsCount++;
                    int simMin = getSimulatedMinutesElapsed();
                    System.out.println(String.format("[T+%dmin] NEWS-DRIVEN (%d/%d): %s", 
                        simMin, newsCount, maxNews, news.split(":")[1]));
                }
                
                scheduleNextNews();
            } else {
                block(100);
            }
        }
        
        @Override
        public boolean done() {
            return isSessionEnded() || newsCount >= maxNews;
        }
    }
    
    /**
     * Comportement aleatoire pour scenario de panique
     */
    private class RandomPanicScenarioBehaviour extends Behaviour {
        private int newsCount = 0;
        private long nextNewsTime;
        private final int maxNews;
        private final int panicPhaseCount;
        
        public RandomPanicScenarioBehaviour(Agent a) {
            super(a);
            maxNews = Math.max(10, simulatedDurationMinutes / 2);
            panicPhaseCount = Math.max(3, maxNews / 3);
            scheduleNextNews();
            System.out.println("Scenario PANIQUE: max " + maxNews + " actualites sur " + simulatedDurationMinutes + " min simulees");
        }
        
        private void scheduleNextNews() {
            if (newsCount <= panicPhaseCount) {
                long panicDelay = adjustInterval(2000 + random.nextInt(3000));
                nextNewsTime = System.currentTimeMillis() + panicDelay;
            } else {
                nextNewsTime = System.currentTimeMillis() + getRandomNewsDelay();
            }
        }
        
        @Override
        public void action() {
            if (isSessionEnded() || newsCount >= maxNews) {
                return;
            }
            
            if (System.currentTimeMillis() >= nextNewsTime && newsCount < maxNews) {
                if (newsCount <= panicPhaseCount) {
                    String[] panicNews = {
                        "NEGATIVE:FLASH CRASH - AAPL plonge sur un catalyseur inconnu:IMPACT:HIGH",
                        "NEGATIVE:La panique vendeuse se propage dans le secteur tech:IMPACT:HIGH",
                        "NEGATIVE:Suspensions de trading declenchees par une volatilite extreme:IMPACT:HIGH"
                    };
                    
                    String news = panicNews[random.nextInt(panicNews.length)];
                    broadcastNews(news);
                    newsCount++;
                    int simMin = getSimulatedMinutesElapsed();
                    System.out.println(String.format("[T+%dmin] PHASE PANIQUE (%d/%d): %s", 
                        simMin, newsCount, maxNews, news.split(":")[1]));
                    
                } else {
                    String[] recoveryNews = {
                        "NEUTRAL:Les teneurs de marche interviennent pour fournir de la liquidite:IMPACT:MEDIUM",
                        "POSITIVE:Des investisseurs value commencent a accumuler AAPL:IMPACT:MEDIUM",
                        "NEUTRAL:La volatilite diminue alors que la panique s'apaise:IMPACT:LOW",
                        "POSITIVE:La direction d'AAPL rassure les investisseurs:IMPACT:HIGH",
                        "POSITIVE:Le marche trouve un support aux niveaux techniques cles:IMPACT:MEDIUM"
                    };
                    
                    if (shouldPublishNews()) {
                        String news = recoveryNews[random.nextInt(recoveryNews.length)];
                        broadcastNews(news);
                        newsCount++;
                        int simMin = getSimulatedMinutesElapsed();
                        System.out.println(String.format("[T+%dmin] PHASE RECUPERATION (%d/%d): %s", 
                            simMin, newsCount, maxNews, news.split(":")[1]));
                    }
                }
                
                scheduleNextNews();
            } else {
                block(100);
            }
        }
        
        @Override
        public boolean done() {
            if (isSessionEnded() || newsCount >= maxNews) {
                System.out.println("Scenario de panique termine - Marche stabilise");
                return true;
            }
            return false;
        }
    }
    
    /**
     * Diffuse les actualites a tous les abonnes
     */
    private void broadcastNews(String news) {
        if (isSessionEnded()) return;
        
        List<AID> allSubscribers = new ArrayList<>(subscribers);
        if (!allSubscribers.contains(new AID("MarketChart", AID.ISLOCALNAME))) {
            allSubscribers.add(new AID("MarketChart", AID.ISLOCALNAME));
        }
        
        if (allSubscribers.isEmpty()) {
            return;
        }
        
        ACLMessage newsMsg = new ACLMessage(ACLMessage.INFORM);
        newsMsg.setProtocol("NEWS");
        newsMsg.setContent(news);
        
        newsMsg.addReceiver(new AID("MarketMaker", AID.ISLOCALNAME));
        
        for (AID subscriber : allSubscribers) {
            newsMsg.addReceiver(subscriber);
        }
        
        send(newsMsg);
    }
    
    /**
     * Enregistre le service dans le Directory Facilitator
     */
    private void registerService() {
        DFAgentDescription dfd = new DFAgentDescription();
        dfd.setName(getAID());
        ServiceDescription sd = new ServiceDescription();
        sd.setType("news-provider");
        sd.setName("market-news");
        dfd.addServices(sd);
        
        try {
            DFService.register(this, dfd);
        } catch (FIPAException e) {
            e.printStackTrace();
        }
    }
    
    /**
     * Recherche les abonnes potentiels
     */
    private void findSubscribers() {
        try {
            DFAgentDescription template = new DFAgentDescription();
            ServiceDescription sd = new ServiceDescription();
            sd.setType("market-maker");
            template.addServices(sd);
            
            DFAgentDescription[] marketMakers = DFService.search(this, template);
            for (DFAgentDescription dfd : marketMakers) {
                subscribers.add(dfd.getName());
            }
            
        } catch (FIPAException e) {
            e.printStackTrace();
        }
    }
    
    /**
     * Initialise les templates d'actualites pour chaque phase
     */
    private void initializeNewsTemplates() {
        newsTemplates = new HashMap<>();
        
        List<NewsTemplate> calmNews = Arrays.asList(
            new NewsTemplate("NEUTRAL", "AAPL publie des resultats trimestriels conformes aux attentes", "LOW"),
            new NewsTemplate("POSITIVE", "Le secteur technologique affiche une croissance moderee", "LOW"),
            new NewsTemplate("NEUTRAL", "La Reserve Federale maintient sa politique actuelle", "MEDIUM"),
            new NewsTemplate("POSITIVE", "AAPL annonce des ameliorations mineures de produits", "LOW"),
            new NewsTemplate("NEUTRAL", "Les analystes maintiennent leurs notes sur les valeurs tech", "LOW")
        );
        newsTemplates.put(MarketPhase.CALM, calmNews);
        
        List<NewsTemplate> trendNews = Arrays.asList(
            new NewsTemplate("POSITIVE", "AAPL devoile une technologie IA revolutionnaire en developpement", "MEDIUM"),
            new NewsTemplate("POSITIVE", "Des investisseurs institutionnels augmentent leurs positions AAPL", "MEDIUM"),
            new NewsTemplate("POSITIVE", "La demande pour les produits AAPL depasse les previsions", "HIGH"),
            new NewsTemplate("POSITIVE", "AAPL s'associe a des geants tech pour son expansion", "MEDIUM"),
            new NewsTemplate("POSITIVE", "Les analystes revaluent AAPL a l'achat fort", "HIGH")
        );
        newsTemplates.put(MarketPhase.EMERGING_TREND, trendNews);
        
        List<NewsTemplate> bubbleNews = Arrays.asList(
            new NewsTemplate("POSITIVE", "AAPL atteint un record historique sur volumes eleves", "HIGH"),
            new NewsTemplate("POSITIVE", "Les investisseurs particuliers se ruent sur AAPL", "HIGH"),
            new NewsTemplate("POSITIVE", "La capitalisation d'AAPL franchit un jalon historique", "HIGH"),
            new NewsTemplate("NEUTRAL", "La volatilite augmente alors qu'AAPL s'envole", "MEDIUM"),
            new NewsTemplate("POSITIVE", "L'engouement des reseaux sociaux alimente l'achat d'AAPL", "HIGH")
        );
        newsTemplates.put(MarketPhase.BUBBLE, bubbleNews);
        
        List<NewsTemplate> crashNews = Arrays.asList(
            new NewsTemplate("NEGATIVE", "AAPL fait face a une enquete reglementaire inattendue", "HIGH"),
            new NewsTemplate("NEGATIVE", "Decouverte d'une faille de securite majeure chez AAPL", "HIGH"),
            new NewsTemplate("NEGATIVE", "Un dirigeant cle d'AAPL annonce son depart soudain", "HIGH"),
            new NewsTemplate("NEGATIVE", "Les prises de benefices declenchent une vente massive d'AAPL", "HIGH"),
            new NewsTemplate("NEGATIVE", "Les craintes de correction se propagent au secteur tech", "HIGH")
        );
        newsTemplates.put(MarketPhase.CRASH, crashNews);
        
        List<NewsTemplate> stabilizationNews = Arrays.asList(
            new NewsTemplate("NEUTRAL", "AAPL trouve un support aux niveaux techniques cles", "MEDIUM"),
            new NewsTemplate("POSITIVE", "Les investisseurs value commencent a accumuler AAPL", "MEDIUM"),
            new NewsTemplate("NEUTRAL", "La volatilite diminue alors que la pression vendeuse s'apaise", "LOW"),
            new NewsTemplate("POSITIVE", "La direction d'AAPL rassure les investisseurs sur les fondamentaux", "MEDIUM"),
            new NewsTemplate("NEUTRAL", "Les analystes jugent AAPL correctement valorise aux niveaux actuels", "LOW")
        );
        newsTemplates.put(MarketPhase.STABILIZATION, stabilizationNews);
    }
    
    @Override
    protected void takeDown() {
        try {
            DFService.deregister(this);
        } catch (FIPAException e) {
            e.printStackTrace();
        }
        
        long totalMinutesReal = (System.currentTimeMillis() - sessionStartTime) / 60000;
        System.out.println("\n=== NewsProvider Arret ===");
        System.out.println("Duree reelle: " + totalMinutesReal + " minute(s)");
        System.out.println("Duree simulee: " + simulatedDurationMinutes + " minutes");
        System.out.println("Phase finale: " + currentPhase);
        System.out.println("==========================\n");
    }
}