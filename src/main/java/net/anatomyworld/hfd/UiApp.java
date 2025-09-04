package net.anatomyworld.hfd;

import com.formdev.flatlaf.FlatClientProperties;
import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.plaf.ColorUIResource;
import javax.swing.plaf.FontUIResource;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

public class UiApp extends Main {

    // Dark banana–caramel theme
    private static final Color C_BROWN        = new Color(0x2E1C14);
    private static final Color C_CARAMEL      = new Color(0x9E5833);
    private static final Color C_YELLOW_DARK  = new Color(0xC7A243);

    // Text colors
    private static final Color TXT_PRIMARY    = new Color(0xE2CFA6);
    private static final Color TXT_SECONDARY  = new Color(0xD1BC8C);
    private static final Color TXT_LOG        = new Color(0xDCCBA3);

    // Translucent “glass” for logs
    private static final Color LOG_GLASS_BG   = new Color(18, 10, 6, 215);
    private static final Color LOG_BG_SOLID   = new Color(LOG_GLASS_BG.getRed(), LOG_GLASS_BG.getGreen(), LOG_GLASS_BG.getBlue()); // #120A06

    // Button palette
    private static final Color BTN_BG         = LOG_BG_SOLID;
    private static final Color BTN_FG         = TXT_PRIMARY;

    private JTextArea logArea;
    private JLabel pathLabel;
    private BananaBar bananaBar;
    private HaloOnHoverButton installBtn; // CHANGED: keep the subtype so we can stop its animation

    // Fonts (Minecraft)
    private Font uiFont;
    private Font titleFont;

    public void open() {
        setupLookAndFeel();

        JFrame f = new JFrame("HFD Installer");
        f.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE); // we'll handle a clean exit ourselves
        f.setSize(860, 560);
        f.setMinimumSize(new Dimension(760, 520));
        f.setLocationRelativeTo(null);

        // On close button → perform clean shutdown then System.exit(0)
        f.addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) {
                shutdownAndExit();
            }
        });

        // Window icon (multi-size)
        BufferedImage baseIcon = readPngIcon();
        if (baseIcon != null) f.setIconImages(makeIconVariants(baseIcon));

        GradientBackground root = new GradientBackground(
                new Color[]{ C_BROWN, C_CARAMEL, C_YELLOW_DARK },
                new float[]{ 0f, .58f, 1f }
        );
        root.setLayout(new BorderLayout());
        root.setBorder(new EmptyBorder(24, 24, 24, 24));

        // ===== Center =====
        JPanel center = new JPanel();
        center.setOpaque(false);
        center.setLayout(new BoxLayout(center, BoxLayout.Y_AXIS));

        JLabel logo = new JLabel();
        logo.setAlignmentX(Component.CENTER_ALIGNMENT);
        if (baseIcon != null) {
            logo.setIcon(new ImageIcon(makeRounded(baseIcon, 84, 22)));
        }

        JLabel sentence = new JLabel("Install HarambeFD to the Minecraft launcher");
        sentence.setAlignmentX(Component.CENTER_ALIGNMENT);
        sentence.setForeground(TXT_PRIMARY);
        sentence.setFont(titleFont);

        JPanel pathRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 0));
        pathRow.setOpaque(false);

        pathLabel = new JLabel(ellipsize(defaultMinecraftDir().toString(), 48));
        pathLabel.setForeground(TXT_SECONDARY);
        pathLabel.setFont(uiFont.deriveFont(Font.PLAIN, 13f));

        JLabel change = linkLabelPlain("Change folder");
        change.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                JFileChooser ch = new JFileChooser(expandPathFromLabel(pathLabel.getText(), defaultMinecraftDir().toString()));
                ch.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
                if (ch.showOpenDialog(f) == JFileChooser.APPROVE_OPTION) {
                    pathLabel.setText(ellipsize(ch.getSelectedFile().getAbsolutePath(), 48));
                }
            }
        });
        pathRow.add(pathLabel);
        pathRow.add(change);

        installBtn = new HaloOnHoverButton("Install");
        installBtn.setAlignmentX(Component.CENTER_ALIGNMENT);
        styleInstallButton(installBtn);
        installBtn.setFont(uiFont.deriveFont(Font.BOLD, 16f));

        bananaBar = new BananaBar(loadBananaImage());
        bananaBar.setAlignmentX(Component.CENTER_ALIGNMENT);
        bananaBar.setVisible(false);

        center.add(Box.createVerticalGlue());
        if (logo.getIcon() != null) {
            center.add(logo);
            center.add(Box.createVerticalStrut(12));
        }
        center.add(sentence);
        center.add(Box.createVerticalStrut(10));
        center.add(pathRow);
        center.add(Box.createVerticalStrut(18));
        center.add(installBtn);
        center.add(Box.createVerticalStrut(10));
        center.add(bananaBar);
        center.add(Box.createVerticalGlue());

        // ===== South: logs =====
        JPanel logCard = new GlassCard(LOG_GLASS_BG);
        logCard.setLayout(new BorderLayout());
        logCard.setBorder(new EmptyBorder(10, 12, 10, 12));

        logArea = new JTextArea(6, 20);
        logArea.setEditable(false);
        logArea.setLineWrap(true);
        logArea.setWrapStyleWord(true);
        logArea.setFont(uiFont.deriveFont(Font.PLAIN, 13f));
        logArea.setForeground(TXT_LOG);
        logArea.setOpaque(false);
        logArea.setCaretColor(TXT_LOG);
        logArea.setSelectedTextColor(new Color(0x1A120A));
        logArea.setSelectionColor(new Color(0xE0C27A));

        JScrollPane sp = new JScrollPane(logArea);
        sp.setOpaque(false);
        sp.getViewport().setOpaque(false);
        sp.setBorder(BorderFactory.createEmptyBorder());
        logCard.add(sp, BorderLayout.CENTER);

        root.add(center, BorderLayout.CENTER);
        root.add(logCard, BorderLayout.SOUTH);

        installBtn.addActionListener(e -> runInstallAsync(f));

        f.setContentPane(root);
        f.setVisible(true);

        // Also ensure timers stop on JVM shutdown (belt & suspenders)
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            bananaBar.setRunning(false);
            installBtn.stopHalo();
        }, "shutdown-cleanup"));
    }

    // ---------- actions ----------

    private void runInstallAsync(JFrame f) {
        installBtn.setEnabled(false);
        bananaBar.setVisible(true);
        bananaBar.setRunning(true);

        Thread t = new Thread(() -> {
            try {
                Path mc = Paths.get(expandPathFromLabel(pathLabel.getText(), defaultMinecraftDir().toString()));
                Installer.Log logger = s -> SwingUtilities.invokeLater(() -> {
                    logArea.append(s + "\n");
                    logArea.setCaretPosition(logArea.getDocument().getLength());
                });
                new Installer().runInstall(mc, logger);

                SwingUtilities.invokeLater(() -> {
                    // Stop & hide progress BEFORE the dialog
                    bananaBar.setRunning(false);
                    bananaBar.setVisible(false);

                    int sel = JOptionPane.showOptionDialog(
                            f,
                            "HFD installed, Open or Restart the Minecraft Launcher ",
                            "Done",
                            JOptionPane.DEFAULT_OPTION,
                            JOptionPane.INFORMATION_MESSAGE,
                            null,
                            new Object[]{"OK"},
                            "OK"
                    );
                    if (sel == JOptionPane.OK_OPTION || sel == 0) {
                        switchInstallButtonToDone();
                    } else {
                        installBtn.setEnabled(true);
                    }
                });
            } catch (Exception ex) {
                ex.printStackTrace();
                SwingUtilities.invokeLater(() ->
                        JOptionPane.showMessageDialog(
                                f,
                                "Installation failed:\n" + ex.getMessage(),
                                "Error",
                                JOptionPane.ERROR_MESSAGE
                        )
                );
                SwingUtilities.invokeLater(() -> {
                    installBtn.setEnabled(true);
                    bananaBar.setRunning(false);
                    bananaBar.setVisible(false);
                });
            }
        }, "installer");
        t.start();
    }

    private void switchInstallButtonToDone() {
        for (ActionListener al : installBtn.getActionListeners()) installBtn.removeActionListener(al);
        installBtn.setText("Done");
        installBtn.putClientProperty(FlatClientProperties.STYLE,
                "arc:2; focusWidth:1; innerFocusWidth:0; background:#120A06; foreground:#E2CFA6;");
        installBtn.setEnabled(true);
        installBtn.addActionListener(evt -> shutdownAndExit()); // CHANGED: hard exit to release JAR lock
    }

    /** Cleanly stop animations and terminate the VM so Windows releases the JAR. */
    private void shutdownAndExit() {
        try { bananaBar.setRunning(false); } catch (Exception ignored) {}
        try { installBtn.stopHalo(); } catch (Exception ignored) {}
        // Dispose any window (optional)
        Window w = SwingUtilities.getWindowAncestor(installBtn);
        if (w != null) w.dispose();
        System.exit(0); // guarantees process ends, releasing any file locks on the running JAR (Windows)
    }

    // ---------- look & feel + fonts ----------

    private void setupLookAndFeel() {
        FlatLaf.setGlobalExtraDefaults(java.util.Map.of("@accentColor", "#F08A5D"));
        FlatDarkLaf.setup();

        Font[] pair = loadFontPair(
                "embedded/fonts/Minecraft.otf",
                "embedded/fonts/Minecraft-Bold.otf",
                16f,  // body
                22f   // title
        );
        uiFont = pair[0];
        titleFont = pair[1];

        UIManager.put("defaultFont", new FontUIResource(uiFont));
        UIManager.put("Component.focusWidth", 1);
        UIManager.put("Button.arc", 2);
        UIManager.put("Component.arc", 8);
        UIManager.put("TextComponent.arc", 8);
        UIManager.put("ProgressBar.arc", 999);

        Color dlgBg  = new Color(0x231611);
        UIManager.put("OptionPane.background",        new ColorUIResource(dlgBg));
        UIManager.put("Panel.background",             new ColorUIResource(dlgBg));
        UIManager.put("OptionPane.messageForeground", new ColorUIResource(TXT_PRIMARY));
        UIManager.put("Label.foreground",             new ColorUIResource(TXT_PRIMARY));
        UIManager.put("Button.background",            new ColorUIResource(new Color(0x9E5833)));
        UIManager.put("Button.foreground",            new ColorUIResource(Color.WHITE));
        UIManager.put("Component.focusColor",         new ColorUIResource(new Color(0xF08A5D)));
    }

    private Font[] loadFontPair(String regularPath, String boldPath, float bodySize, float titleSize) {
        Font regular = tryLoadFont(regularPath, bodySize);
        Font bold    = tryLoadFont(boldPath,   titleSize);
        if (regular == null) regular = new Font("Dialog", Font.PLAIN, Math.round(bodySize));
        if (bold == null)    bold    = regular.deriveFont(Font.BOLD, titleSize);
        return new Font[]{ regular, bold };
    }

    private Font tryLoadFont(String path, float size) {
        try (InputStream in = Main.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) return null;
            Font f = Font.createFont(Font.TRUETYPE_FONT, in);
            return f.deriveFont(Font.PLAIN, size);
        } catch (Exception ignored) {
            return null;
        }
    }

    // ---------- helpers ----------

    private void styleInstallButton(JButton b) {
        b.putClientProperty(FlatClientProperties.STYLE,
                "arc:2; focusWidth:1; innerFocusWidth:0; background:#120A06; foreground:#E2CFA6;");
        b.setBorderPainted(false);
    }

    private JLabel linkLabelPlain(String text) {
        JLabel l = new JLabel(text);
        l.setFont(uiFont.deriveFont(Font.PLAIN, 13f));
        l.setForeground(LOG_BG_SOLID);
        l.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        l.addMouseListener(new MouseAdapter() {
            @Override public void mouseEntered(MouseEvent e) {
                l.setForeground(TXT_PRIMARY);
                l.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, TXT_PRIMARY));
            }
            @Override public void mouseExited(MouseEvent e)  {
                l.setForeground(LOG_BG_SOLID);
                l.setBorder(BorderFactory.createEmptyBorder());
            }
        });
        return l;
    }

    private static String ellipsize(String s, int max) {
        if (s == null || s.length() <= max) return s;
        int head = (int) Math.round(max * 0.6);
        int tail = max - head - 1;
        return s.substring(0, head) + "…" + s.substring(s.length() - tail);
    }

    private BufferedImage readPngIcon() {
        try (InputStream in = iconStream()) {
            if (in == null) return null;
            return ImageIO.read(in);
        } catch (Exception ignored) {
            return null;
        }
    }

    private Image loadBananaImage() {
        try (InputStream in1 = Main.class.getResourceAsStream("/embedded/banana.png");
             InputStream in2 = in1 == null ? Main.class.getClassLoader().getResourceAsStream("embedded/banana.png") : null) {
            InputStream in = (in1 != null) ? in1 : in2;
            if (in == null) return null;
            return ImageIO.read(in);
        } catch (Exception e) {
            return null;
        }
    }

    private List<Image> makeIconVariants(BufferedImage base) {
        int[] sizes = {16, 20, 24, 32, 40, 48, 64, 128, 256};
        List<Image> variants = new ArrayList<>(sizes.length);
        for (int s : sizes) variants.add(base.getScaledInstance(s, s, Image.SCALE_SMOOTH));
        return variants;
    }

    private static Image makeRounded(BufferedImage src, int size, int arc) {
        BufferedImage scaled = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = scaled.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.drawImage(src, 0, 0, size, size, null);
        g.dispose();

        BufferedImage out = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        Shape clip = new RoundRectangle2D.Float(0, 0, size, size, arc, arc);
        g.setClip(clip);
        g.drawImage(scaled, 0, 0, null);
        g.dispose();
        return out;
    }

    private static String expandPathFromLabel(String labelText, String fallback) {
        if (labelText != null && (labelText.startsWith("/") || labelText.matches("^[A-Za-z]:\\\\.*")))
            return labelText.replace("…", "");
        return fallback;
    }

    // ===== visuals =====

    private static final class GradientBackground extends JPanel {
        private final Color[] colors;
        private final float[] fractions;
        GradientBackground(Color[] colors, float[] fracs) { this.colors = colors; this.fractions = fracs; setOpaque(false); }
        @Override protected void paintComponent(Graphics g) {
            int w = getWidth(), h = getHeight();
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            Paint gp = new LinearGradientPaint(0, 0, w, h, fractions, colors);
            g2.setPaint(gp);
            g2.fillRect(0, 0, w, h);
            g2.dispose();
            super.paintComponent(g);
        }
    }

    private static final class GlassCard extends JPanel {
        private final Color bg;
        GlassCard(Color bg) { this.bg = bg; setOpaque(false); }
        @Override protected void paintComponent(Graphics g) {
            int w = getWidth(), h = getHeight();
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(bg);
            g2.fillRoundRect(0, 0, w, h, 16, 16);
            g2.dispose();
            super.paintComponent(g);
        }
    }

    /** A slim, animated “banana” progress bar. */
    private static final class BananaBar extends JComponent {
        private final Image bananaSrc;
        private float pos = 0f;
        private float dir = 1f;
        private boolean running = false;
        private final javax.swing.Timer timer;

        BananaBar(Image banana) {
            this.bananaSrc = banana;
            setOpaque(false);
            setPreferredSize(new Dimension(260, 18));
            int fps = 60;
            timer = new javax.swing.Timer(1000 / fps, e -> {
                if (!running) return;
                pos += dir * 0.02f;
                if (pos > 1f) { pos = 1f; dir = -1f; }
                if (pos < 0f) { pos = 0f; dir = 1f; }
                repaint();
            });
            timer.setCoalesce(true);
        }

        void setRunning(boolean r) {
            running = r;
            if (r) { if (!timer.isRunning()) timer.start(); }
            else    { if (timer.isRunning())  timer.stop(); }
            repaint();
        }

        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            int w = getWidth();
            int h = getHeight();
            int radius = Math.min(12, h);

            g2.setColor(new Color(255, 255, 255, 38));
            g2.fillRoundRect(0, h/2 - 3, w, 6, radius, radius);

            if (bananaSrc != null) {
                int bH = Math.max(10, h - 4);
                int bW = (int) (bH * 1.0);
                int x = (int) (pos * (w - bW));
                int y = (h - bH) / 2;

                g2.setComposite(AlphaComposite.SrcOver.derive(0.15f));
                int trailSteps = 6;
                for (int i = trailSteps; i >= 1; i--) {
                    int tx = (int) (x - (dir * i * (w / 100.0)));
                    float alpha = (i / (float)trailSteps) * 0.15f;
                    g2.setComposite(AlphaComposite.SrcOver.derive(alpha));
                    g2.drawImage(bananaSrc, tx, y, bW, bH, null);
                }
                g2.setComposite(AlphaComposite.SrcOver);
                g2.drawImage(bananaSrc, x, y, bW, bH, null);
            } else {
                g2.setColor(new Color(255, 235, 130));
                int x = (int) (pos * w);
                g2.fillOval(x - 4, h/2 - 4, 8, 8);
            }
            g2.dispose();
        }
    }

    /** JButton with a smooth, subtle halo on hover/press (no size or color change). */
    private static final class HaloOnHoverButton extends JButton {
        private float halo = 0f;
        private final javax.swing.Timer anim;
        private final Color haloColor = new Color(0xE2, 0xCF, 0xA6);

        HaloOnHoverButton(String text) {
            super(text);
            setOpaque(false);
            setRolloverEnabled(true);

            anim = new javax.swing.Timer(16, e -> {
                ButtonModel m = getModel();
                float target = m.isPressed() ? 1.0f : (m.isRollover() ? 0.7f : 0f);
                halo += (target - halo) * 0.18f;
                if (Math.abs(target - halo) < 0.005f) halo = target;
                if (halo > 0f) repaint();
                else if (m.isRollover() || m.isPressed()) repaint();
            });
            anim.start();

            setMargin(new Insets(8, 22, 8, 22));
        }

        void stopHalo() { anim.stop(); } // allow caller to stop the animation timer

        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            if (halo > 0f) {
                int w = getWidth(), h = getHeight();
                int arc = 8;
                for (int i = 6; i >= 1; i--) {
                    float a = halo * (i / 60f);
                    g2.setComposite(AlphaComposite.SrcOver.derive(a));
                    g2.setColor(haloColor);
                    g2.fillRoundRect(-i, -i, w + 2*i, h + 2*i, arc + i*2, arc + i*2);
                }
                g2.setComposite(AlphaComposite.SrcOver);
            }
            super.paintComponent(g2);
            g2.dispose();
        }
    }
}
