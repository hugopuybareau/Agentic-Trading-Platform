package src.agents;

import jade.core.Agent;
import jade.core.AID;
import jade.core.behaviours.CyclicBehaviour;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.ArrayList;
import java.util.List;

/**
 * Agent d'affichage graphique du marche en temps reel.
 * Visualise l'evolution des prix, les transactions executees et les actualites.
 */
public class MarketChartAgent extends Agent {
    private MarketChartFrame frame;
    private String symbol = "AAPL";
    private String outputDir = "charts";

    @Override
    protected void setup() {
        Object[] args = getArguments();
        if (args != null && args.length > 0 && args[0] != null) {
            symbol = args[0].toString();
        }

        // Creation du repertoire de sortie pour les graphiques
        new File(outputDir).mkdirs();

        final String title = "Trading Platform - " + symbol;
        SwingUtilities.invokeLater(() -> {
            frame = new MarketChartFrame(title);
            frame.setVisible(true);
        });

        addBehaviour(new MarketDataListener());
        addBehaviour(new TradeExecutedListener());
        addBehaviour(new NewsListener()); 
        sendMarketDataSubscribe();

        addBehaviour(new CyclicBehaviour() {
            @Override
            public void action() {
                MessageTemplate mt = MessageTemplate.and(
                    MessageTemplate.MatchProtocol("MARKET-DATA-SUB"),
                    MessageTemplate.MatchPerformative(ACLMessage.CONFIRM)
                );
                ACLMessage msg = receive(mt);
                if (msg != null) {
                    System.out.println(getLocalName() + " subscribed to MARKET-DATA");
                } else {
                    block();
                }
            }
        });

        System.out.println(getLocalName() + " started for symbol " + symbol);
    }

    private void sendMarketDataSubscribe() {
        ACLMessage sub = new ACLMessage(ACLMessage.SUBSCRIBE);
        sub.setProtocol("MARKET-DATA-SUB");
        sub.addReceiver(new AID("MarketMaker", AID.ISLOCALNAME));
        sub.setContent(symbol);
        send(sub);
        System.out.println(getLocalName() + " -> SUBSCRIBE MARKET-DATA");
    }

    @Override
    protected void takeDown() {
        if (frame != null) {
            String ts = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
            File file = new File(outputDir, symbol + "_" + ts + ".png");
            try {
                SwingUtilities.invokeAndWait(() -> {
                    try {
                        frame.panel.savePNG(file);
                        System.out.println(getLocalName() + " chart saved to " + file.getPath());
                    } catch (Exception e) {
                        System.err.println("Save chart error: " + e.getMessage());
                    }
                });
            } catch (Exception e) {
                System.err.println("EDT invoke error: " + e.getMessage());
            }
            SwingUtilities.invokeLater(() -> frame.dispose());
        }
        System.out.println(getLocalName() + " shutting down");
    }

    // Ecoute et traitement des donnees de marche
    private class MarketDataListener extends CyclicBehaviour {
        @Override
        public void action() {
            MessageTemplate mt = MessageTemplate.MatchProtocol("MARKET-DATA");
            ACLMessage msg = receive(mt);
            if (msg != null) {
                String content = msg.getContent();
                try {
                    double price = parseCurrentPrice(content);
                    long now = System.currentTimeMillis();
                    SwingUtilities.invokeLater(() -> {
                        if (frame != null) frame.panel.addPoint(now, price);
                    });
                } catch (Exception e) {
                    System.err.println(getLocalName() + " parse error: " + content + " -> " + e.getMessage());
                }
            } else {
                block();
            }
        }

        private double parseCurrentPrice(String content) {
            String[] p = content.split(":");
            int start = 0;
            for (int i = 0; i < p.length; i++) {
                if ("MARKET_DATA".equalsIgnoreCase(p[i]) || "PRICE".equalsIgnoreCase(p[i])) {
                    start = i + 2;
                    break;
                }
            }
            for (int i = start; i < p.length; i++) {
                try { 
                    return Double.parseDouble(p[i].replace(",", ".")); 
                } catch (NumberFormatException ignore) {}
            }
            for (int i = p.length - 1; i >= 0; i--) {
                try { 
                    return Double.parseDouble(p[i].replace(",", ".")); 
                } catch (NumberFormatException ignore) {}
            }
            throw new IllegalArgumentException("No numeric price token");
        }
    }

    // Ecoute et affichage des transactions executees
    private class TradeExecutedListener extends CyclicBehaviour {
        @Override
        public void action() {
            MessageTemplate mt = MessageTemplate.MatchProtocol("TRADE-EXECUTED");
            ACLMessage msg = receive(mt);
            if (msg != null) {
                String content = msg.getContent();
                try {
                    boolean isBuy = content.contains("BUY");
                    Double px = extractPrice(content);
                    if (px != null) {
                        long now = System.currentTimeMillis();
                        SwingUtilities.invokeLater(() -> {
                            if (frame != null) frame.panel.addTradeMarker(now, px, isBuy);
                        });
                    }
                } catch (Exception e) {
                    System.err.println(getLocalName() + " trade parse error: " + content + " -> " + e.getMessage());
                }
            } else {
                block();
            }
        }

        private Double extractPrice(String content) {
            String[] t = content.split(":");
            for (int i = t.length - 1; i >= 0; i--) {
                try { 
                    return Double.parseDouble(t[i].replace(",", ".")); 
                } catch (NumberFormatException ignore) {}
            }
            return null;
        }
    }

    // Ecoute et affichage des actualites
    private class NewsListener extends CyclicBehaviour {
        @Override
        public void action() {
            MessageTemplate mt = MessageTemplate.MatchProtocol("NEWS");
            ACLMessage msg = receive(mt);
            if (msg != null) {
                String content = msg.getContent();
                try {
                    // Format: "SENTIMENT:Description:IMPACT:LEVEL"
                    String[] parts = content.split(":");
                    if (parts.length >= 4) {
                        String sentiment = parts[0]; // POSITIVE, NEGATIVE, NEUTRAL
                        String description = parts[1];
                        String impactLevel = parts[3]; // LOW, MEDIUM, HIGH
                        
                        long now = System.currentTimeMillis();
                        SwingUtilities.invokeLater(() -> {
                            if (frame != null) {
                                frame.panel.addNewsMarker(now, sentiment, description, impactLevel);
                            }
                        });
                        
                        System.out.println(getLocalName() + " - News received: " + sentiment + " (" + impactLevel + ")");
                    }
                } catch (Exception e) {
                    System.err.println(getLocalName() + " news parse error: " + content + " -> " + e.getMessage());
                }
            } else {
                block();
            }
        }
    }

    /**
     * Fenetre principale du graphique de marche.
     */
    static class MarketChartFrame extends JFrame {
        final MarketChartPanel panel;

        MarketChartFrame(String title) {
            super(title);
            setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
            setSize(1200, 700);
            setLocationRelativeTo(null);
            
            // Configuration du theme Nimbus si disponible
            try {
                for (UIManager.LookAndFeelInfo info : UIManager.getInstalledLookAndFeels()) {
                    if ("Nimbus".equals(info.getName())) {
                        UIManager.setLookAndFeel(info.getClassName());
                        break;
                    }
                }
            } catch (Exception e) {
                try {
                    UIManager.setLookAndFeel("javax.swing.plaf.metal.MetalLookAndFeel");
                } catch (Exception ignored) {}
            }
            
            panel = new MarketChartPanel();
            setContentPane(panel);
        }
    }

    /**
     * Classe pour stocker les informations d'actualites
     */
    static class NewsMarker {
        long timestamp;
        String sentiment;
        String description;
        String impactLevel;
        
        NewsMarker(long timestamp, String sentiment, String description, String impactLevel) {
            this.timestamp = timestamp;
            this.sentiment = sentiment;
            this.description = description;
            this.impactLevel = impactLevel;
        }
    }

    /**
     * Panel de dessin du graphique financier.
     */
    static class MarketChartPanel extends JPanel {
        private final List<Long> times = new ArrayList<>();
        private final List<Double> prices = new ArrayList<>();
        private final List<Long> tradeTimes = new ArrayList<>();
        private final List<Double> tradePrices = new ArrayList<>();
        private final List<Boolean> tradeIsBuy = new ArrayList<>();
        private final List<NewsMarker> newsMarkers = new ArrayList<>();

        // Palette de couleurs financieres
        private static final Color BACKGROUND = new Color(18, 18, 24);
        private static final Color GRID_COLOR = new Color(40, 44, 52);
        private static final Color TEXT_COLOR = new Color(171, 178, 191);
        private static final Color PRICE_LINE = new Color(33, 150, 243);
        private static final Color BUY_COLOR = new Color(76, 175, 80);
        private static final Color SELL_COLOR = new Color(244, 67, 54);
        private static final Color BORDER_COLOR = new Color(60, 63, 65);
        private static final Color NEWS_POSITIVE = new Color(139, 195, 74);
        private static final Color NEWS_NEGATIVE = new Color(255, 87, 34); 
        private static final Color NEWS_NEUTRAL = new Color(158, 158, 158);

        MarketChartPanel() {
            setBackground(BACKGROUND);
            setDoubleBuffered(true);
            
            // Tooltip pour afficher les details des actualites
            setToolTipText("");
        }

        synchronized void addPoint(long t, double p) {
            times.add(t);
            prices.add(p);
            repaint();
        }

        synchronized void addTradeMarker(long t, double p, boolean isBuy) {
            tradeTimes.add(t);
            tradePrices.add(p);
            tradeIsBuy.add(isBuy);
            repaint();
        }

        // Ajouter un marqueur d'actualite
        synchronized void addNewsMarker(long t, String sentiment, String description, String impactLevel) {
            newsMarkers.add(new NewsMarker(t, sentiment, description, impactLevel));
            repaint();
        }

        // Tooltip dynamique pour les actualites
        @Override
        public String getToolTipText(java.awt.event.MouseEvent e) {
            synchronized (this) {
                if (newsMarkers.isEmpty() || times.isEmpty()) return null;
                
                int w = getWidth(), h = getHeight();
                int left = 80, top = 60;
                int plotW = w - left - 80;
                
                long tMin = times.get(0);
                long tMax = times.get(times.size() - 1);
                if (tMax == tMin) return null;
                
                int mouseX = e.getX();
                
                // Recherche de l'actualite la plus proche du curseur
                for (NewsMarker news : newsMarkers) {
                    int x = left + (int) ((news.timestamp - tMin) * plotW / (double) (tMax - tMin));
                    
                    if (Math.abs(mouseX - x) < 15) {
                        String sentiment = news.sentiment.equals("POSITIVE") ? "📈 Positif" :
                                         news.sentiment.equals("NEGATIVE") ? "📉 Négatif" : "➡️ Neutre";
                        return "<html><b>" + sentiment + " (" + news.impactLevel + ")</b><br>" +
                               news.description + "</html>";
                    }
                }
            }
            return null;
        }

        public void savePNG(File file) throws Exception {
            int w = Math.max(1200, getWidth());
            int h = Math.max(700, getHeight());
            BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            Graphics2D g2 = img.createGraphics();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            this.setSize(w, h);
            paint(g2);
            g2.dispose();
            ImageIO.write(img, "png", file);
        }

        @Override
        protected void paintComponent(Graphics g0) {
            super.paintComponent(g0);
            Graphics2D g = (Graphics2D) g0;
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            List<Long> tCopy;
            List<Double> pCopy;
            List<Long> tt;
            List<Double> tp;
            List<Boolean> tb;
            List<NewsMarker> newsCopy;
            
            synchronized (this) {
                tCopy = new ArrayList<>(times);
                pCopy = new ArrayList<>(prices);
                tt = new ArrayList<>(tradeTimes);
                tp = new ArrayList<>(tradePrices);
                tb = new ArrayList<>(tradeIsBuy);
                newsCopy = new ArrayList<>(newsMarkers);
            }
            
            if (pCopy.isEmpty()) {
                drawNoDataMessage(g);
                return;
            }

            int w = getWidth(), h = getHeight();
            int left = 80, right = 80, top = 60, bottom = 60;
            int plotW = w - left - right;
            int plotH = h - top - bottom;

            if (plotW <= 0 || plotH <= 0) return;

            double min = pCopy.stream().min(Double::compareTo).orElse(0.0);
            double max = pCopy.stream().max(Double::compareTo).orElse(1.0);
            double range = max - min;
            if (range == 0) { 
                min -= 1; 
                max += 1; 
                range = 2; 
            }
            
            // Ajout de padding a l'echelle des prix
            double padding = range * 0.1;
            min -= padding;
            max += padding;
            range = max - min;

            long tMin = tCopy.get(0);
            long tMax = tCopy.get(tCopy.size() - 1);
            if (tMax == tMin) tMax = tMin + 1;

            drawHeader(g, w, pCopy.get(pCopy.size() - 1));
            
            g.setColor(BORDER_COLOR);
            g.setStroke(new BasicStroke(2f));
            g.drawRect(left, top, plotW, plotH);

            drawGrid(g, left, top, plotW, plotH);
            drawPriceLabels(g, left, top, plotH, min, max);
            drawTimeLabels(g, left, top, plotW, plotH, tMin, tMax);
            
            // Dessiner les actualites AVANT la ligne de prix (en arriere-plan)
            drawNewsMarkers(g, newsCopy, left, top, plotW, plotH, tMin, tMax);
            
            drawPriceLine(g, tCopy, pCopy, left, top, plotW, plotH, tMin, tMax, min, max);
            drawTradeMarkers(g, tt, tp, tb, left, top, plotW, plotH, tMin, tMax, min, max);
        }

        private void drawNoDataMessage(Graphics2D g) {
            g.setColor(TEXT_COLOR);
            g.setFont(new Font("SansSerif", Font.PLAIN, 16));
            FontMetrics fm = g.getFontMetrics();
            String msg = "Waiting for market data...";
            int x = (getWidth() - fm.stringWidth(msg)) / 2;
            int y = getHeight() / 2;
            g.drawString(msg, x, y);
        }

        private void drawHeader(Graphics2D g, int w, double currentPrice) {
            g.setColor(TEXT_COLOR);
            g.setFont(new Font("SansSerif", Font.BOLD, 24));
            g.drawString("AAPL", 20, 35);
            
            g.setFont(new Font("SansSerif", Font.BOLD, 20));
            g.setColor(PRICE_LINE);
            String priceStr = String.format("$%.2f", currentPrice);
            g.drawString(priceStr, 100, 35);
            
            // Indicateur temps reel
            g.setColor(BUY_COLOR);
            g.fillOval(w - 30, 15, 10, 10);
            g.setColor(TEXT_COLOR);
            g.setFont(new Font("SansSerif", Font.PLAIN, 12));
            g.drawString("LIVE", w - 60, 25);
        }

        private void drawGrid(Graphics2D g, int left, int top, int plotW, int plotH) {
            g.setColor(GRID_COLOR);
            g.setStroke(new BasicStroke(1f));
            
            // Lignes horizontales
            for (int i = 0; i <= 10; i++) {
                int y = top + (plotH * i / 10);
                g.drawLine(left, y, left + plotW, y);
            }
            
            // Lignes verticales
            for (int i = 0; i <= 10; i++) {
                int x = left + (plotW * i / 10);
                g.drawLine(x, top, x, top + plotH);
            }
        }

        private void drawPriceLabels(Graphics2D g, int left, int top, int plotH, double min, double max) {
            g.setColor(TEXT_COLOR);
            g.setFont(new Font("Monospaced", Font.PLAIN, 11));
            FontMetrics fm = g.getFontMetrics();
            
            for (int i = 0; i <= 10; i++) {
                double price = max - (max - min) * i / 10.0;
                int y = top + (plotH * i / 10);
                String label = String.format("%.2f", price);
                int labelWidth = fm.stringWidth(label);
                g.drawString(label, left - labelWidth - 10, y + 4);
            }
        }

        private void drawTimeLabels(Graphics2D g, int left, int top, int plotW, int plotH, long tMin, long tMax) {
            g.setColor(TEXT_COLOR);
            g.setFont(new Font("SansSerif", Font.PLAIN, 10));
            SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss");
            
            for (int i = 0; i <= 5; i++) {
                long time = tMin + (tMax - tMin) * i / 5;
                int x = left + (plotW * i / 5);
                String label = timeFormat.format(new Date(time));
                FontMetrics fm = g.getFontMetrics();
                int labelWidth = fm.stringWidth(label);
                g.drawString(label, x - labelWidth/2, top + plotH + 20);
            }
        }

        // Dessiner les marqueurs d'actualites
        private void drawNewsMarkers(Graphics2D g, List<NewsMarker> news, 
                                    int left, int top, int plotW, int plotH,
                                    long tMin, long tMax) {
            if (news.isEmpty()) return;
            
            g.setStroke(new BasicStroke(2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_BEVEL, 
                                      0, new float[]{5, 5}, 0));
            
            for (NewsMarker marker : news) {
                int x = left + (int) ((marker.timestamp - tMin) * plotW / (double) (tMax - tMin));
                
                // Determiner la couleur selon le sentiment
                Color newsColor;
                switch (marker.sentiment) {
                    case "POSITIVE":
                        newsColor = NEWS_POSITIVE;
                        break;
                    case "NEGATIVE":
                        newsColor = NEWS_NEGATIVE;
                        break;
                    default:
                        newsColor = NEWS_NEUTRAL;
                        break;
                }
                
                // Ligne verticale en pointilles
                g.setColor(new Color(newsColor.getRed(), newsColor.getGreen(), 
                                   newsColor.getBlue(), 120));
                g.drawLine(x, top, x, top + plotH);
                
                // Icone en haut du graphique
                g.setColor(newsColor);
                int iconSize = marker.impactLevel.equals("HIGH") ? 12 : 
                              marker.impactLevel.equals("MEDIUM") ? 10 : 8;
                
                if (marker.sentiment.equals("POSITIVE")) {
                    // Triangle vers le haut pour positif
                    int[] xPoints = {x, x - iconSize/2, x + iconSize/2};
                    int[] yPoints = {top - 15, top - 5, top - 5};
                    g.fillPolygon(xPoints, yPoints, 3);
                } else if (marker.sentiment.equals("NEGATIVE")) {
                    // Triangle vers le bas pour negatif
                    int[] xPoints = {x, x - iconSize/2, x + iconSize/2};
                    int[] yPoints = {top - 5, top - 15, top - 15};
                    g.fillPolygon(xPoints, yPoints, 3);
                } else {
                    // Cercle pour neutre
                    g.fillOval(x - iconSize/2, top - 15, iconSize, iconSize);
                }
                
                // Texte de l'impact si HIGH
                if (marker.impactLevel.equals("HIGH")) {
                    g.setFont(new Font("SansSerif", Font.BOLD, 9));
                    g.setColor(TEXT_COLOR);
                    g.drawString("!", x - 2, top - 18);
                }
            }
            
            g.setStroke(new BasicStroke(1.5f));
        }

        private void drawPriceLine(Graphics2D g, List<Long> times, List<Double> prices, 
                                 int left, int top, int plotW, int plotH, 
                                 long tMin, long tMax, double min, double max) {
            if (prices.size() < 2) return;

            // Zone remplie sous la courbe avec gradient
            Path2D.Double area = new Path2D.Double();
            int startX = left + (int) ((times.get(0) - tMin) * plotW / (double) (tMax - tMin));
            int startY = top + (int) ((max - prices.get(0)) / (max - min) * plotH);
            area.moveTo(startX, top + plotH);
            area.lineTo(startX, startY);
            
            for (int i = 1; i < prices.size(); i++) {
                int x = left + (int) ((times.get(i) - tMin) * plotW / (double) (tMax - tMin));
                int y = top + (int) ((max - prices.get(i)) / (max - min) * plotH);
                area.lineTo(x, y);
            }
            
            int endX = left + (int) ((times.get(prices.size()-1) - tMin) * plotW / (double) (tMax - tMin));
            area.lineTo(endX, top + plotH);
            area.closePath();
            
            g.setPaint(new Color(33, 150, 243, 30));
            g.fill(area);
            
            // Ligne de prix principale
            g.setStroke(new BasicStroke(2.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(PRICE_LINE);
            
            Path2D.Double path = new Path2D.Double();
            for (int i = 0; i < prices.size(); i++) {
                int x = left + (int) ((times.get(i) - tMin) * plotW / (double) (tMax - tMin));
                int y = top + (int) ((max - prices.get(i)) / (max - min) * plotH);
                if (i == 0) path.moveTo(x, y);
                else path.lineTo(x, y);
            }
            g.draw(path);
        }

        private void drawTradeMarkers(Graphics2D g, List<Long> tradeTimes, List<Double> tradePrices, 
                                    List<Boolean> tradeIsBuy, int left, int top, int plotW, int plotH,
                                    long tMin, long tMax, double min, double max) {
            g.setStroke(new BasicStroke(1.5f));
            
            for (int i = 0; i < tradeTimes.size(); i++) {
                int x = left + (int) ((tradeTimes.get(i) - tMin) * plotW / (double) (tMax - tMin));
                int y = top + (int) ((max - tradePrices.get(i)) / (max - min) * plotH);
                boolean isBuy = tradeIsBuy.get(i);
                
                g.setColor(isBuy ? BUY_COLOR : SELL_COLOR);
                
                if (isBuy) {
                    // Fleche d'achat (vers le haut)
                    int[] xPoints = {x, x - 8, x + 8};
                    int[] yPoints = {y - 10, y + 5, y + 5};
                    g.fillPolygon(xPoints, yPoints, 3);
                    g.setColor(Color.WHITE);
                    g.drawString("B", x - 3, y + 2);
                } else {
                    // Fleche de vente (vers le bas)
                    int[] xPoints = {x, x - 8, x + 8};
                    int[] yPoints = {y + 10, y - 5, y - 5};
                    g.fillPolygon(xPoints, yPoints, 3);
                    g.setColor(Color.WHITE);
                    g.drawString("S", x - 3, y + 2);
                }
                
                // Ligne de niveau de prix
                g.setColor(isBuy ? BUY_COLOR : SELL_COLOR);
                g.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_BEVEL, 0, new float[]{3, 3}, 0));
                g.drawLine(left, y, left + plotW, y);
                g.setStroke(new BasicStroke(1.5f));
            }
        }
    }
}