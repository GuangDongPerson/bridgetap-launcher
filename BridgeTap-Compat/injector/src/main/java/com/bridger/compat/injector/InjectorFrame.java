package com.bridger.compat.injector;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.IOException;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

/** Double-click friendly front end: pick the game process, press inject, watch the log. */
final class InjectorFrame {
   private final JFrame frame = new JFrame("BridgeTap 注入器 " + AgentPayload.version());
   private final DefaultListModel<Injector.Target> model = new DefaultListModel<Injector.Target>();
   private final JList<Injector.Target> list = new JList<Injector.Target>(this.model);
   private final JTextArea logArea = new JTextArea();
   private final JLabel status = new JLabel("正在扫描进程...");
   private final JButton injectButton = new JButton("注入选中进程");
   private File agentJar;

   private InjectorFrame() {
   }

   static void showGui() {
      try {
         UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
      } catch (Throwable ignored) {
         // keep the default LAF
      }
      SwingUtilities.invokeLater(() -> new InjectorFrame().build());
   }

   private void build() {
      this.frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

      JLabel header = new JLabel("先把 Lunar / Badlion 开到主界面，选中进程后点注入；没有日志就刷新再试。");
      header.setBorder(BorderFactory.createEmptyBorder(10, 12, 6, 12));

      this.list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
      this.list.setCellRenderer((jlist, value, index, selected, focused) -> {
         JLabel label = new JLabel(value == null ? "" : value.toString());
         label.setOpaque(true);
         label.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
         label.setFont(label.getFont().deriveFont(Font.PLAIN, 12f));
         if (selected) {
            label.setBackground(new Color(0x2D6CDF));
            label.setForeground(Color.WHITE);
         } else {
            label.setBackground(Color.WHITE);
            label.setForeground(value != null && value.kind.rank >= 2 ? new Color(0x1B5E20) : Color.DARK_GRAY);
         }
         return label;
      });
      this.list.addMouseListener(new MouseAdapter() {
         @Override
         public void mouseClicked(MouseEvent event) {
            if (event.getClickCount() == 2) {
               injectSelected();
            }
         }
      });

      JPanel buttons = new JPanel(new BorderLayout(8, 0));
      JButton refresh = new JButton("刷新进程");
      refresh.addActionListener(e -> reload());
      this.injectButton.addActionListener(e -> injectSelected());
      buttons.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));
      buttons.add(refresh, BorderLayout.WEST);
      buttons.add(this.injectButton, BorderLayout.EAST);

      JPanel top = new JPanel(new BorderLayout());
      top.add(header, BorderLayout.NORTH);
      JScrollPane listScroll = new JScrollPane(this.list);
      listScroll.setBorder(BorderFactory.createEmptyBorder(0, 12, 0, 12));
      listScroll.setPreferredSize(new Dimension(700, 150));
      top.add(listScroll, BorderLayout.CENTER);
      top.add(buttons, BorderLayout.SOUTH);

      this.logArea.setEditable(false);
      this.logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
      JScrollPane logScroll = new JScrollPane(this.logArea);
      logScroll.setBorder(BorderFactory.createTitledBorder("日志"));

      JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, top, logScroll);
      split.setResizeWeight(0.45);
      split.setBorder(null);

      this.status.setBorder(BorderFactory.createEmptyBorder(4, 12, 6, 12));

      this.frame.add(split, BorderLayout.CENTER);
      this.frame.add(this.status, BorderLayout.SOUTH);
      this.frame.setSize(760, 520);
      this.frame.setLocationRelativeTo(null);

      try {
         this.agentJar = AgentPayload.extract();
         log("agent 已释放到 " + this.agentJar.getAbsolutePath());
      } catch (IOException e) {
         log("内置 agent 释放失败: " + e.getMessage());
         this.injectButton.setEnabled(false);
      }

      reload();
      this.frame.setVisible(true);
   }

   private void reload() {
      this.model.clear();
      List<Injector.Target> targets = Injector.list();
      for (Injector.Target target : targets) {
         this.model.addElement(target);
      }

      List<Injector.Target> candidates = Injector.candidates();
      if (candidates.size() == 1) {
         this.list.setSelectedValue(candidates.get(0), true);
         this.status.setText("已自动选中唯一候选：" + candidates.get(0).kind.label + " (pid " + candidates.get(0).pid + ")");
      } else if (candidates.isEmpty()) {
         this.status.setText("没有发现游戏进程（共 " + targets.size() + " 个 Java 进程）。请先启动客户端。");
      } else {
         this.status.setText("发现 " + candidates.size() + " 个候选，请手动选择。");
      }
   }

   private void injectSelected() {
      Injector.Target target = this.list.getSelectedValue();
      if (target == null) {
         this.status.setText("先选中一个进程。");
         return;
      }
      if (this.agentJar == null || !this.agentJar.isFile()) {
         this.status.setText("agent 文件不存在，无法注入。");
         return;
      }

      this.injectButton.setEnabled(false);
      this.status.setText("正在注入 pid " + target.pid + " ...");
      final File agent = this.agentJar;

         new Thread(() -> {
         Diagnose.Baseline baseline = Diagnose.capture();
         boolean ok = Injector.inject(target, agent, line -> SwingUtilities.invokeLater(() -> log(line)));
         if (!ok) {
            SwingUtilities.invokeLater(() -> {
               this.injectButton.setEnabled(true);
               this.status.setText("注入失败，原因见上方日志。");
            });
            return;
         }

         // Injection succeeding only proves the DLL loaded. Wait for the agent to report a frame,
         // otherwise "injected" is a claim without evidence - which is exactly how the last round
         // looked successful while nothing was drawn.
         SwingUtilities.invokeLater(() -> {
            this.status.setText("注入已送达，等待帧回调（最多 12 秒）...");
            log("注入已送达，等待客户端帧回调 ...");
         });
         Diagnose.Result result = Diagnose.afterInjection(baseline, 12_000L);
         SwingUtilities.invokeLater(() -> {
            this.injectButton.setEnabled(true);
            for (String line : result.detail) {
               log("  " + line);
            }
            this.status.setText(result.headline);
            log("");
            log(result.ok ? "== " + result.headline + " ==" : "== " + result.headline + " ==");
            if (result.ok) {
               return;
            }

            // A world that is still loading renders nothing, so "no hook yet" is expected until the
            // player is actually in game. Keep watching instead of declaring failure.
            log("继续监听 3 分钟：进入游戏世界（能看到方块）后状态会自动更新。");
            startWatching(baseline);
         });
      }, "bridgetap-inject").start();
   }

   private void startWatching(Diagnose.Baseline baseline) {
      final int[] ticks = {0};
      javax.swing.Timer timer = new javax.swing.Timer(2000, event -> {
         ticks[0]++;
         String snap = Diagnose.snapshot(baseline);
         if (snap != null) {
            this.status.setText("已生效：" + snap);
            log("== 已生效：" + snap + " ==");
            ((javax.swing.Timer)event.getSource()).stop();
            return;
         }
         if (ticks[0] >= 90) {
            this.status.setText("仍未捕获帧回调：确认已进入游戏世界，或查看 hook.log");
            log("监听结束，未捕获帧回调。");
            ((javax.swing.Timer)event.getSource()).stop();
         } else {
            this.status.setText("监听中（" + (ticks[0] * 2) + "s）：等待客户端渲染帧 ...");
         }
      });
      timer.setRepeats(true);
      timer.start();
   }

   private void log(String line) {
      this.logArea.append(line + "\n");
      this.logArea.setCaretPosition(this.logArea.getDocument().getLength());
   }
}
