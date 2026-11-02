package dev.holmzi.eldervale.updater.helper;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import javax.swing.BorderFactory;
import javax.swing.JFrame;
import javax.swing.ImageIcon;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JTextArea;
import javax.swing.Timer;
import javax.swing.UIManager;
import javax.swing.UnsupportedLookAndFeelException;
import javax.swing.plaf.ColorUIResource;
import javax.swing.plaf.basic.BasicProgressBarUI;
import javax.swing.plaf.metal.DefaultMetalTheme;
import javax.swing.plaf.metal.MetalLookAndFeel;

final class ProgressWindow {
    private record Status(String text, double progress, boolean finished, boolean failed) {}
    private volatile Status status = new Status("Ожидание закрытия Eldervale…", -1, false, false);
    private final JFrame frame;
    private final JTextArea message = new JTextArea(3, 1);
    private final JProgressBar bar = new JProgressBar(0, 1000);
    private final Timer timer;
    private boolean finished;

    ProgressWindow() {
        installDarkTheme();
        frame = new JFrame("Eldervale Updater");
        var iconResource = ProgressWindow.class.getResource("/icon.png");
        if (iconResource != null) {
            var icon = new ImageIcon(iconResource);
            if (icon.getIconWidth() > 0) {
                frame.setIconImage(icon.getImage());
            }
        }
        Color background = new Color(30, 30, 30);
        var panel = new JPanel(new BorderLayout(0, 14));
        panel.setBackground(background);
        panel.setBorder(BorderFactory.createEmptyBorder(20, 20, 18, 20));
        message.setEditable(false);
        message.setFocusable(false);
        message.setLineWrap(true);
        message.setWrapStyleWord(true);
        message.setOpaque(false);
        message.setForeground(new Color(232, 232, 232));
        message.setFont(new Font(Font.DIALOG, Font.PLAIN, 13));
        bar.setUI(new BasicProgressBarUI() {
            @Override
            protected Color getSelectionBackground() {
                return new Color(232, 232, 232);
            }

            @Override
            protected Color getSelectionForeground() {
                return new Color(20, 20, 20);
            }
        });
        bar.setBackground(new Color(55, 55, 55));
        bar.setForeground(new Color(155, 155, 155));
        bar.setBorderPainted(false);
        bar.setStringPainted(false);
        bar.setBorder(null);
        bar.setFont(new Font(Font.DIALOG, Font.PLAIN, 12));
        bar.setPreferredSize(new Dimension(420, 18));
        panel.add(message, BorderLayout.CENTER);
        panel.add(bar, BorderLayout.SOUTH);
        frame.setContentPane(panel);
        frame.setSize(480, 180);
        frame.setMinimumSize(new Dimension(380, 180));
        frame.setLocationRelativeTo(null);
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent event) {
                closeIfFinished();
            }
        });
        timer = new Timer(100, event -> refresh());
        timer.start();
        refresh();
        frame.setVisible(true);
    }

    private static void installDarkTheme() {
        MetalLookAndFeel.setCurrentTheme(new DefaultMetalTheme() {
            private final ColorUIResource dark = new ColorUIResource(30, 30, 30);
            private final ColorUIResource surface = new ColorUIResource(42, 42, 42);
            private final ColorUIResource edge = new ColorUIResource(75, 75, 75);
            private final ColorUIResource text = new ColorUIResource(232, 232, 232);

            @Override
            protected ColorUIResource getPrimary1() { return edge; }
            @Override
            protected ColorUIResource getPrimary2() { return edge; }
            @Override
            protected ColorUIResource getPrimary3() { return surface; }
            @Override
            protected ColorUIResource getSecondary1() { return edge; }
            @Override
            protected ColorUIResource getSecondary2() { return surface; }
            @Override
            protected ColorUIResource getSecondary3() { return dark; }
            @Override
            public ColorUIResource getControlTextColor() { return text; }
            @Override
            public ColorUIResource getWindowTitleForeground() { return text; }
            @Override
            public ColorUIResource getWindowTitleInactiveForeground() { return text; }
        });
        try {
            UIManager.setLookAndFeel(new MetalLookAndFeel());
        } catch (UnsupportedLookAndFeelException exception) {
            throw new IllegalStateException(exception);
        }
        JFrame.setDefaultLookAndFeelDecorated(false);
    }

    void update(String text, double fraction) {
        status = new Status(text, fraction, false, false);
    }

    void success(String version) {
        status = new Status("Обновление " + version + " завершено.", 1, true, false);
    }

    void failure(String text) {
        status = new Status(text + "\nПодробности: updater/updater.log", 0, true, true);
    }

    private void refresh() {
        Status current = status;
        finished = current.finished();
        message.setText(current.text());
        if (current.failed()) {
            frame.setSize(frame.getWidth(), 250);
        }
        message.setForeground(current.failed() ? new Color(242, 145, 145) : new Color(232, 232, 232));
        bar.setIndeterminate(current.progress() < 0);
        bar.setValue((int) Math.round(Math.max(0, Math.min(1, current.progress())) * 1000));
        if (finished) {
            timer.stop();
        }
    }

    private void closeIfFinished() {
        if (finished) {
            frame.dispose();
            System.exit(0);
        }
    }
}
