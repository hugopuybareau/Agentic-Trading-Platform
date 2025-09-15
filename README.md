# Système de Trading Financier Autonome avec JADE

## 1. Description du Système

Un écosystème de trading où des agents autonomes négocient des actions sur un marché virtuel. Chaque agent trader a sa propre stratégie, ses croyances sur le marché et ses objectifs financiers.

## 2. Architecture BDI des Agents

### 2.1 Agent MarketMaker (Teneur de Marché)
- **Rôle** : Fournit la liquidité, maintient les prix, diffuse les informations de marché
- **Beliefs** : Volume total des transactions, spread bid-ask, volatilité
- **Desires** : Maintenir un marché équilibré, générer des profits sur les spreads
- **Intentions** : Ajuster les prix selon l'offre/demande, publier les cotations

### 2.2 Agent TraderConservateur
- **Beliefs** : Moyennes mobiles, tendances long terme, ratios financiers
- **Desires** : Croissance régulière avec faible risque (5-10% annuel)
- **Intentions** : Acheter sur les baisses, vendre sur les hausses modérées

### 2.3 Agent TraderAgressif
- **Beliefs** : Signaux techniques court terme, momentum, volumes
- **Desires** : Profits élevés (>20%) même avec risque élevé
- **Intentions** : Trading haute fréquence, effet de levier

### 2.4 Agent TraderSuiveur
- **Beliefs** : Actions des autres traders, rumeurs, sentiment général
- **Desires** : Suivre les tendances gagnantes
- **Intentions** : Copier les stratégies des traders performants

### 2.5 Agent NewsProvider
- **Rôle** : Génère des événements d'actualité affectant les prix
- **Intentions** : Diffuser périodiquement des nouvelles (positives/négatives)

## 3. Scénario Dynamique : "La Bulle et le Crash"

### Phase 1 : Marché Calme (0-10 minutes)
- Prix stable autour de 100€
- Traders conservateurs dominent
- Volumes faibles
- Volatilité : 1-2%

### Phase 2 : Émergence d'une Tendance (10-20 minutes)
- NewsProvider diffuse une "bonne nouvelle" sur l'entreprise
- TraderAgressif commence à acheter massivement
- TraderSuiveur observe et commence à imiter
- Prix monte graduellement à 110€
- Volatilité : 3-5%

### Phase 3 : Formation de la Bulle (20-35 minutes)
- Effet de mouton : tous les traders suiveurs achètent
- Rareté des actions → prix monte à 140€
- MarketMaker augmente le spread (risque élevé)
- TraderConservateur devient méfiant, réduit ses positions
- Volatilité : 8-12%

### Phase 4 : Éclatement et Crash (35-45 minutes)
- NewsProvider annonce une "mauvaise nouvelle"
- TraderAgressif déclenche des stop-loss massifs
- Vente de panique : prix chute à 70€
- TraderConservateur profite pour racheter à bas prix
- Volatilité : 15-25%

### Phase 5 : Stabilisation (45-60 minutes)
- Marché trouve un nouvel équilibre autour de 85€
- Leçons apprises intégrées dans les beliefs des agents
- Retour progressif à la normale

## 4. Implémentation JADE

### 4.1 Structure des Messages ACL

```java
// Message de cotation (MarketMaker → tous)
ACLMessage cotation = new ACLMessage(ACLMessage.INFORM);
cotation.setProtocol("MARKET-DATA");
cotation.setContent("PRICE:AAPL:105.50:BID:104.80:ASK:106.20:VOLUME:1500");

// Message d'ordre d'achat (Trader → MarketMaker)  
ACLMessage ordre = new ACLMessage(ACLMessage.REQUEST);
ordre.setProtocol("TRADING");
ordre.setContent("BUY:AAPL:100:SHARES:MARKET_PRICE");

// Message de news (NewsProvider → tous)
ACLMessage news = new ACLMessage(ACLMessage.INFORM);
news.setProtocol("NEWS");
news.setContent("POSITIVE:AAPL annonce des bénéfices records:IMPACT:HIGH");
```

### 4.2 Comportements Principaux

#### MarketMaker - Comportement de Cotation
```java
public class PricingBehaviour extends CyclicBehaviour {
    public void action() {
        // Calculer nouveau prix basé sur ordres
        double newPrice = calculatePrice();
        // Diffuser aux abonnés
        broadcastPrice(newPrice);
        block(1000); // Mise à jour chaque seconde
    }
}
```

#### Trader - Comportement de Décision
```java
public class TradingDecisionBehaviour extends CyclicBehaviour {
    public void action() {
        ACLMessage msg = receive();
        if (msg != null) {
            if ("MARKET-DATA".equals(msg.getProtocol())) {
                updateBeliefs(msg.getContent());
                if (shouldTrade()) {
                    executeTradeStrategy();
                }
            }
        } else {
            block();
        }
    }
}
```

### 4.3 Gestion des Croyances (Beliefs)

```java
public class TradingBeliefs {
    private double currentPrice;
    private double[] priceHistory;
    private double movingAverage;
    private double volatility;
    private String marketSentiment;
    
    public void updatePrice(double newPrice) {
        // Mise à jour historique et calculs techniques
        this.currentPrice = newPrice;
        updatePriceHistory(newPrice);
        calculateMovingAverage();
        calculateVolatility();
    }
    
    public boolean isBullishTrend() {
        return currentPrice > movingAverage && volatility < 0.1;
    }
}
```

### 4.4 Protocole d'Interaction

1. **Inscription** : Traders s'enregistrent auprès du MarketMaker
2. **Abonnement** : Demande de réception des cotations
3. **Trading** : Envoi d'ordres d'achat/vente
4. **Confirmation** : MarketMaker confirme les transactions
5. **Reporting** : Diffusion périodique des statistiques

## 5. Métriques de Performance

### 5.1 Indicateurs de Marché
- Prix moyen par période
- Volatilité (écart-type des rendements)
- Volume total échangé
- Nombre de transactions

### 5.2 Performance des Agents
- P&L (Profit & Loss) de chaque trader
- Ratio de Sharpe (rendement/risque)
- Nombre de trades gagnants/perdants
- Exposition maximale

## 6. Extensions Possibles

### 6.1 Fonctionnalités Avancées
- Ordres à cours limité vs marché
- Effet de levier et margin calls
- Multiple assets (portfolio)
- Corrélations entre actions

### 6.2 Stratégies Plus Sophistiquées
- Mean reversion
- Momentum trading
- Pairs trading
- Market making dynamique

### 6.3 Facteurs Externes
- Cycles économiques
- Événements géopolitiques
- Données macro-économiques
- Sentiment des réseaux sociaux

## 7. Défis d'Implémentation

1. **Synchronisation** : Gérer la simultanéité des ordres
2. **Performance** : Optimiser pour la haute fréquence
3. **Réalisme** : Calibrer les paramètres sur données réelles
4. **Visualisation** : Interface graphique temps réel
5. **Logs** : Traçabilité complète pour analyse post-mortem

Cette architecture permet de créer un écosystème riche et dynamique où émergent des comportements complexes à partir d'interactions simples entre agents autonomes.
