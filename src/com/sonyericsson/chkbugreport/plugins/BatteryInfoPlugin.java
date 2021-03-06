/*
 * Copyright (C) 2011 Sony Ericsson Mobile Communications AB
 *
 * This file is part of ChkBugReport.
 *
 * ChkBugReport is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 2 of the License, or
 * (at your option) any later version.
 *
 * ChkBugReport is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with ChkBugReport.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.sonyericsson.chkbugreport.plugins;

import java.awt.Color;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.imageio.ImageIO;

import com.sonyericsson.chkbugreport.Bug;
import com.sonyericsson.chkbugreport.BugReport;
import com.sonyericsson.chkbugreport.Chapter;
import com.sonyericsson.chkbugreport.Plugin;
import com.sonyericsson.chkbugreport.ProcessRecord;
import com.sonyericsson.chkbugreport.Report;
import com.sonyericsson.chkbugreport.Section;
import com.sonyericsson.chkbugreport.Util;
import com.sonyericsson.chkbugreport.util.DumpTree;
import com.sonyericsson.chkbugreport.util.DumpTree.Node;
import com.sonyericsson.chkbugreport.util.TableGen;

public class BatteryInfoPlugin extends Plugin {

    private static final String TAG = "[BatteryInfoPlugin]";

    private static final Signal SIGNALS[] = new Signal[]{
        new Signal("charging", Signal.TYPE_BIN, null, null),
        new Signal("wake_lock", Signal.TYPE_BIN, null, null),
        new Signal("screen", Signal.TYPE_BIN, null, null),
        new Signal("edge", Signal.TYPE_BIN, null, null),
        new Signal("umts", Signal.TYPE_BIN, null, null),
        new Signal("hsdpa", Signal.TYPE_BIN, null, null),
        new Signal("hspa", Signal.TYPE_BIN, null, null),
    };

    private static final int GRAPH_W = 800;
    private static final int GRAPH_H = 300;
    private static final int GRAPH_PX = 700;
    private static final int GRAPH_PY = 250;
    private static final int GRAPH_PW = 600;
    private static final int GRAPH_PH = 200;
    private static final int GRAPH_SH = 25;
    private static final int GRAPH_BG = 10;

    private static final int MAX_LEVEL = 110;

    private static final Color COL_SIGNAL = Color.BLACK;
    private static final Color COL_SIGNAL_PART = Color.GRAY;

    private static final int MS = 1;
    private static final int SEC = 1000 * MS;
    private static final int MIN = 60 * SEC;
    private static final int HOUR = 60 * MIN;
    private static final int DAY = 24 * HOUR;

    private static final long WAKE_LOG_BUG_THRESHHOLD = 1 * HOUR;

    private long mMaxTs;

    private Graphics2D mG;

    static class Signal {
        public static final int TYPE_BIN = 0;
        public static final int TYPE_INT = 1;
        public static final int TYPE_PRC = 2;

        private String mName;
        private int mType;
        private String[] mValueNames;
        private int[] mValues;
        private long mTs;
        private int mValue;

        public Signal(String name, int type, String valueNames[], int values[]) {
            mName = name;
            mType = type;
            mValueNames = valueNames;
            mValues = values;
            mTs = -1;
            mValue = -1;
        }

        public String getName() {
            return mName;
        }

        public int getType() {
            return mType;
        }

        public String[] getValueNames() {
            return mValueNames;
        }

        public int[] getValues() {
            return mValues;
        }

        public int getValue() {
            return mValue;
        }

        public long getTs() {
            return mTs;
        }

        public void setValue(long ts, int value) {
            mTs = ts;
            mValue = value;
        }
    }

    static class CpuPerUid {
        long usr;
        long krn;
        String uidLink;
        String uidName;
    }

    @Override
    public int getPrio() {
        return 90;
    }

    @Override
    public void load(Report br) {
        // NOP
    }

    @Override
    public void generate(Report rep) {
        BugReport br = (BugReport) rep;

        Section sec = br.findSection(Section.DUMP_OF_SERVICE_BATTERYINFO);
        if (sec == null) {
            br.printErr(TAG + "Section not found: " + Section.DUMP_OF_SERVICE_BATTERYINFO + " (aborting plugin)");
            return;
        }

        // Create the main chapter
        Chapter ch = new Chapter(br, "Battery info");

        // Find the battery history
        int idx = 0;
        int cnt = sec.getLineCount();
        boolean foundBatteryHistory = false;
        while (idx < cnt) {
            String buff = sec.getLine(idx++);
            if (buff.equals("Battery History:")) {
                foundBatteryHistory = true;
                break;
            }
        }

        if (!foundBatteryHistory) {
            br.printErr(TAG + "Battery history not found in section " + Section.DUMP_OF_SERVICE_BATTERYINFO);
            idx = 0;
        } else {
            // Create the image
            int totalH = GRAPH_H + GRAPH_SH * SIGNALS.length + GRAPH_BG;
            BufferedImage img = new BufferedImage(GRAPH_W, totalH, BufferedImage.TYPE_INT_RGB);
            mG = (Graphics2D)img.getGraphics();
            mG.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            mG.setColor(Color.WHITE);
            mG.fillRect(0, 0, GRAPH_W, totalH);
            mG.setColor(Color.BLACK);
            mG.drawRect(0, 0, GRAPH_W - 1, totalH - 1);

            // Draw the axis
            int as = 5;
            mG.setColor(Color.BLACK);
            mG.drawLine(GRAPH_PX, GRAPH_PY, GRAPH_PX, GRAPH_PY - GRAPH_PH);
            mG.drawLine(GRAPH_PX, GRAPH_PY, GRAPH_PX - GRAPH_PW, GRAPH_PY);
            mG.drawLine(GRAPH_PX - as, GRAPH_PY - GRAPH_PH + as, GRAPH_PX, GRAPH_PY - GRAPH_PH);
            mG.drawLine(GRAPH_PX + as, GRAPH_PY - GRAPH_PH + as, GRAPH_PX, GRAPH_PY - GRAPH_PH);
            mG.drawLine(GRAPH_PX - GRAPH_PW + as, GRAPH_PY - as, GRAPH_PX - GRAPH_PW, GRAPH_PY);
            mG.drawLine(GRAPH_PX - GRAPH_PW + as, GRAPH_PY + as, GRAPH_PX - GRAPH_PW, GRAPH_PY);

            // Draw the title
            FontMetrics fm = mG.getFontMetrics();
            mG.drawString("Battery history", 10, 10 + fm.getAscent());

            // Draw some guide lines
            Color colGuide = new Color(0xc0c0ff);
            for (int value = 25; value <= 100; value += 25) {
                int yv = toY(value);
                mG.setColor(colGuide);
                mG.drawLine(GRAPH_PX - 1, yv, GRAPH_PX - GRAPH_PW, yv);
                mG.setColor(Color.BLACK);
                String s = "" + value + "%";
                mG.drawString(s, GRAPH_PX + 1, yv);
            }

            // Read the battery history and plot the chart
            mMaxTs = -1;
            long lastTs = -1;
            int lastLevelX = -1;
            int lastLevelY = -1;
            Color colLevel = new Color(0x000000);
            while (idx < cnt) {
                String buff = sec.getLine(idx++);
                if (buff.length() == 0) {
                    break;
                }

                // Read the timestamp
                long ts = readTs(buff.substring(0, 21));
                lastTs = ts;
                if (mMaxTs == -1) {
                    mMaxTs = ts * 110 / 100;
                }
                // Read the battery level
                String levelS = buff.substring(22, 25);
                if (levelS.charAt(0) == ' ') continue; // there is a disturbance in the force...
                int level = Integer.parseInt(levelS);

                // Plot the level
                int levelX = toX(ts);
                int levelY = toY(level);
                if (lastLevelX != -1 && lastLevelY != -1) {
                    mG.setColor(colLevel);
                    mG.drawLine(lastLevelX, lastLevelY, levelX, levelY);
                }

                // Parse the signal levels
                if (buff.length() > 35) {
                    buff = buff.substring(35);
                    String signals[] = buff.split(" ");
                    for (String s : signals) {
                        char c = s.charAt(0);
                        if (c == '+') {
                            addSignal(ts, s.substring(1), 1);
                        } else if (c == '-') {
                            addSignal(ts, s.substring(1), 0);
                        } else {
                            int eq = s.indexOf('=');
                            if (eq > 0) {
                                String value = s.substring(eq + 1);
                                s = s.substring(0, eq);
                                addSignal(ts, s, value);
                            }
                        }
                    }
                }

                lastLevelX = levelX;
                lastLevelY = levelY;
            }

            // Finish off every signal
            for (int i = 0; i < SIGNALS.length; i++) {
                addSignal(lastTs, SIGNALS[i].getName(), -1);
            }

            // Draw labels on time axis
            long step = 30*60*1000L;
            int count = (int)(mMaxTs / step);
            while (count > 10) {
                step *= 2;
                count = (int)(mMaxTs / step);
            }
            for (long ts = step; ts <= mMaxTs; ts += step) {
                int xv = toX(ts);
                int hour = (int)(ts / (60*60*1000L));
                int min = (int)((ts / (60*1000L)) % 60);
                mG.setColor(Color.BLACK);
                mG.drawLine(xv, GRAPH_PY, xv, GRAPH_PY + 5);
                String s = String.format("%d:%02d", hour, min);
                mG.drawString(s, xv, GRAPH_PY + fm.getAscent());
            }

            // Finish and save the graph
            String fn = br.getRelDataDir() + "batteryhistory.png";
            try {
                ImageIO.write(img, "png", new File(br.getBaseDir() + fn));
            } catch (IOException e) {
                e.printStackTrace();
                return;
            }

            // Add the graph
            Chapter cch = new Chapter(br, "Battery History");
            ch.addChapter(cch);
            cch.addLine("<div><img src=\"" + fn + "\"/></div>");
        }

        // Parse the rest as indented dump tree
        DumpTree dump = new DumpTree(sec, idx);

        // Extract the "Per-PID Stats"
        DumpTree.Node node = dump.find("Per-PID Stats:");
        if (node != null) {
            ch.addChapter(genPerPidStats(br, node));
        }

        // Extract the total statistics (eclair)
        node = dump.find("Total Statistics (Current and Historic):");
        if (node != null) {
            Chapter child = new Chapter(br, "Total Statistics (Current and Historic)");
            ch.addChapter(child);
            genStats(br, child, node, false);
        }

        // Extract the last run statistics (eclair)
        node = dump.find("Last Run Statistics (Previous run of system):");
        if (node != null) {
            Chapter child = new Chapter(br, "Last Run Statistics (Previous run of system)");
            ch.addChapter(child);
            genStats(br, child, node, true);
        }

        // Extract the statistics since last charge
        node = dump.find("Statistics since last charge:");
        if (node != null) {
            Chapter child = new Chapter(br, "Statistics since last charge");
            ch.addChapter(child);
            genStats(br, child, node, true);
        }

        // Extract the statistics since last unplugged
        node = dump.find("Statistics since last unplugged:");
        if (node != null) {
            Chapter child = new Chapter(br, "Statistics since last unplugged");
            ch.addChapter(child);
            genStats(br, child, node, true);
        }

        if (!ch.isEmpty()) {
            br.addChapter(ch);
        }

    }

    private void genStats(BugReport br, Chapter ch, Node node, boolean detectBugs) {
        PackageInfoPlugin pkgInfo = (PackageInfoPlugin) br.getPlugin("PackageInfoPlugin");
        Bug bug = null;
        ch.addLine("<pre>");

        // Prepare the kernelWakeLock table
        Chapter kernelWakeLock = new Chapter(br, "Kernel Wake locks");
        Pattern pKWL = Pattern.compile(".*?\"(.*?)\": (.*?) \\((.*?) times\\)");
        TableGen tgKWL = new TableGen(kernelWakeLock, TableGen.FLAG_SORT);
        tgKWL.addColumn("Kernel Wake lock", TableGen.FLAG_NONE);
        tgKWL.addColumn("Count", TableGen.FLAG_ALIGN_RIGHT);
        tgKWL.addColumn("Time", TableGen.FLAG_ALIGN_RIGHT);
        tgKWL.addColumn("Time(ms)", TableGen.FLAG_ALIGN_RIGHT);
        tgKWL.begin();

        // Prepare the wake lock table
        Chapter wakeLock = new Chapter(br, "Wake locks");
        wakeLock.addLine("<div class=\"hint\">(Hint: hover over the UID to see it's name.)</div>");
        Pattern pWL = Pattern.compile("Wake lock (.*?): (.*?) ([a-z]+) \\((.*?) times\\)");
        TableGen tgWL = new TableGen(wakeLock, TableGen.FLAG_SORT);
        tgWL.addColumn("UID", TableGen.FLAG_ALIGN_RIGHT);
        tgWL.addColumn("Wake lock", TableGen.FLAG_NONE);
        tgWL.addColumn("Type", TableGen.FLAG_NONE);
        tgWL.addColumn("Count", TableGen.FLAG_ALIGN_RIGHT);
        tgWL.addColumn("Time", TableGen.FLAG_ALIGN_RIGHT);
        tgWL.addColumn("Time(ms)", TableGen.FLAG_ALIGN_RIGHT);
        tgWL.begin();

        // Prepare the CPU per UID table
        Chapter cpuPerUid = new Chapter(br, "CPU usage per UID");
        cpuPerUid.addLine("<div class=\"hint\">(Hint: hover over the UID to see it's name.)</div>");
        Pattern pProc = Pattern.compile("Proc (.*?):");
        Pattern pCPU = Pattern.compile("CPU: (.*?) usr \\+ (.*?) krn");
        TableGen tgCU = new TableGen(cpuPerUid, TableGen.FLAG_SORT);
        tgCU.addColumn("UID", TableGen.FLAG_ALIGN_RIGHT);
        tgCU.addColumn("Usr (ms)", TableGen.FLAG_ALIGN_RIGHT);
        tgCU.addColumn("Krn (ms)", TableGen.FLAG_ALIGN_RIGHT);
        tgCU.addColumn("Total (ms)", TableGen.FLAG_ALIGN_RIGHT);
        tgCU.addColumn("Usr (min)", TableGen.FLAG_ALIGN_RIGHT);
        tgCU.addColumn("Krn (min)", TableGen.FLAG_ALIGN_RIGHT);
        tgCU.addColumn("Total (min)", TableGen.FLAG_ALIGN_RIGHT);
        tgCU.begin();

        // Prepare the CPU per Proc table
        Chapter cpuPerProc = new Chapter(br, "CPU usage per Proc");
        cpuPerProc.addLine("<div class=\"hint\">(Hint: hover over the UID to see it's name.)</div>");
        TableGen tgCP = new TableGen(cpuPerProc, TableGen.FLAG_SORT);
        tgCP.addColumn("UID", TableGen.FLAG_ALIGN_RIGHT);
        tgCP.addColumn("Proc", TableGen.FLAG_NONE);
        tgCP.addColumn("Usr", TableGen.FLAG_ALIGN_RIGHT);
        tgCP.addColumn("Usr (ms)", TableGen.FLAG_ALIGN_RIGHT);
        tgCP.addColumn("Krn", TableGen.FLAG_ALIGN_RIGHT);
        tgCP.addColumn("Krn (ms)", TableGen.FLAG_ALIGN_RIGHT);
        tgCP.addColumn("Total (ms)", TableGen.FLAG_ALIGN_RIGHT);
        tgCP.begin();

        // Prepare the network traffic table
        Chapter net = new Chapter(br, "Network traffic");
        net.addLine("<div class=\"hint\">(Hint: hover over the UID to see it's name.)</div>");
        Pattern pNet = Pattern.compile("Network: (.*?) received, (.*?) sent");
        TableGen tgNet = new TableGen(net, TableGen.FLAG_SORT);
        tgNet.addColumn("UID", TableGen.FLAG_ALIGN_RIGHT);
        tgNet.addColumn("Received (B)", TableGen.FLAG_ALIGN_RIGHT);
        tgNet.addColumn("Send (B)", TableGen.FLAG_ALIGN_RIGHT);
        tgNet.addColumn("Total (B)", TableGen.FLAG_ALIGN_RIGHT);
        tgNet.begin();

        // Process the data
        long sumRecv = 0, sumSent = 0;
        HashMap<String, CpuPerUid> cpuPerUidStats = new HashMap<String, CpuPerUid>();
        for (Node item : node) {
            String line = item.getLine();
            if (line.startsWith("#")) {
                String sUID = line.substring(1, line.length() - 1);
                PackageInfoPlugin.UID uid = null;
                String uidName = sUID;
                String uidLink = null;
                if (pkgInfo != null) {
                    int uidInt = Integer.parseInt(sUID);
                    uid = pkgInfo.getUID(uidInt);
                    if (uid != null) {
                        uidName = uid.getFullName();
                        uidLink = pkgInfo.getLinkToUid(br, uid);
                    }
                }

                // Collect wake lock and network traffic data
                for (Node subNode : item) {
                    String s = subNode.getLine();
                    if (s.startsWith("Wake lock") && !s.contains("(nothing executed)")) {
                        Matcher m = pWL.matcher(s);
                        if (m.find()) {
                            String name = m.group(1);
                            String sTime = m.group(2);
                            String type = m.group(3);
                            String sCount = m.group(4);
                            long ts = readTs(sTime.replace(" ", ""));
                            tgWL.setNextRowStyle(colorizeTime(ts));
                            tgWL.addData(uidLink, uidName, sUID, TableGen.FLAG_NONE);
                            tgWL.addData(name);
                            tgWL.addData(type);
                            tgWL.addData(sCount);
                            tgWL.addData(sTime);
                            tgWL.addData(Util.shadeValue(ts));
                            if (ts > WAKE_LOG_BUG_THRESHHOLD) {
                                bug = createBug(br, bug);
                                bug.addLine("<li>Wake lock: " + name + "</li>");
                            }
                        } else {
                            System.err.println("Could not parse line: " + s);
                        }
                    } else if (s.startsWith("Network: ")) {
                        Matcher m = pNet.matcher(s);
                        if (m.find()) {
                            long recv = parseBytes(m.group(1));
                            long sent = parseBytes(m.group(2));
                            sumRecv += recv;
                            sumSent += sent;
                            tgNet.addData(uidLink, uidName, sUID, TableGen.FLAG_NONE);
                            tgNet.addData(Util.shadeValue(recv));
                            tgNet.addData(Util.shadeValue(sent));
                            tgNet.addData(Util.shadeValue(recv + sent));
                        } else {
                            System.err.println("Could not parse line: " + s);
                        }
                    } else if (s.startsWith("Proc ")) {
                        Matcher mProc = pProc.matcher(s);
                        if (mProc.find()) {
                            String procName = mProc.group(1);
                            Node cpuItem = subNode.findChildStartsWith("CPU:");
                            if (cpuItem != null) {
                                Matcher m = pCPU.matcher(cpuItem.getLine());
                                if (m.find()) {
                                    String sUsr = m.group(1);
                                    long usr = readTs(sUsr.replace(" ", ""));
                                    String sKrn = m.group(2);
                                    long krn = readTs(sKrn.replace(" ", ""));

                                    CpuPerUid cpu = cpuPerUidStats.get(sUID);
                                    if (cpu == null) {
                                        cpu = new CpuPerUid();
                                        cpu.uidLink = uidLink;
                                        cpu.uidName = uidName;
                                        cpuPerUidStats.put(sUID, cpu);
                                    }
                                    cpu.usr += usr;
                                    cpu.krn += krn;

                                    tgCP.addData(uidLink, uidName, sUID, TableGen.FLAG_NONE);
                                    tgCP.addData(procName);
                                    tgCP.addData(sUsr);
                                    tgCP.addData(Util.shadeValue(usr));
                                    tgCP.addData(sKrn);
                                    tgCP.addData(Util.shadeValue(krn));
                                    tgCP.addData(Util.shadeValue(usr + krn));
                                } else {
                                    System.err.println("Could not parse line: " + cpuItem.getLine());
                                }
                            }
                        } else {
                            System.err.println("Could not parse line: " + s);
                        }
                    }
                }
            } else if (line.startsWith("Kernel Wake lock")) {
                if (!line.contains("(nothing executed)")) {
                    // Collect into table
                    Matcher m = pKWL.matcher(line);
                    if (m.find()) {
                        String name = m.group(1);
                        String sTime = m.group(2);
                        String sCount = m.group(3);
                        long ts = readTs(sTime.replace(" ", ""));
                        tgKWL.setNextRowStyle(colorizeTime(ts));
                        tgKWL.addData(name);
                        tgKWL.addData(sCount);
                        tgKWL.addData(sTime);
                        tgKWL.addData(Util.shadeValue(ts));
                        if (ts > WAKE_LOG_BUG_THRESHHOLD) {
                            bug = createBug(br, bug);
                            bug.addLine("<li>Kernel Wake lock: " + name + "</li>");
                        }
                    } else {
                        System.err.println("Could not parse line: " + line);
                    }
                }
            } else {
                if (item.getChildCount() == 0) {
                    ch.addLine(line);
                }
            }
        }

        // Build chapter content
        ch.addLine("</pre>");

        if (!tgKWL.isEmpty()) {
            tgKWL.end();
            ch.addChapter(kernelWakeLock);
        }

        if (!tgWL.isEmpty()) {
            tgWL.end();
            ch.addChapter(wakeLock);
        }

        if (!cpuPerUidStats.isEmpty()) {
            for (String sUid : cpuPerUidStats.keySet()) {
                CpuPerUid cpu = cpuPerUidStats.get(sUid);
                tgCU.addData(cpu.uidLink, cpu.uidName, sUid, TableGen.FLAG_NONE);
                tgCU.addData(Util.shadeValue(cpu.usr));
                tgCU.addData(Util.shadeValue(cpu.krn));
                tgCU.addData(Util.shadeValue(cpu.usr + cpu.krn));
                tgCU.addData(Long.toString(cpu.usr / MIN));
                tgCU.addData(Long.toString(cpu.krn / MIN));
                tgCU.addData(Long.toString((cpu.usr + cpu.krn) / MIN));
            }
            tgCU.end();
            ch.addChapter(cpuPerUid);
        }

        if (!tgCP.isEmpty()) {
            tgCP.end();
            ch.addChapter(cpuPerProc);
        }

        if (!tgNet.isEmpty()) {
            tgNet.addSeparator();
            tgNet.addData("TOTAL:");
            tgNet.addData(Util.shadeValue(sumRecv));
            tgNet.addData(Util.shadeValue(sumSent));
            tgNet.addData(Util.shadeValue(sumRecv + sumSent));
            tgNet.end();
            ch.addChapter(net);
        }

        // Finish and add the bug if created
        if (detectBugs && bug != null) {
            bug.addLine("</ul>");
            bug.addLine("<p>" + br.getLinkToChapter(ch) + "Click here for more information</a></p>");
            br.addBug(bug);
        }
    }

    private Bug createBug(BugReport br, Bug bug) {
        if (bug == null) {
            bug = new Bug(Bug.PRIO_POWER_CONSUMPTION, 0, "Suspicious power consumption");
            bug.addLine("<p>Some wakelocks are taken for too long:</p>");
            bug.addLine("<ul>");
        }
        return bug;
    }

    private long parseBytes(String s) {
        long mul = 1;

        if (s.endsWith("MB") || s.endsWith("mb")) {
            s = s.substring(0, s.length() - 2);
            mul = 1024L * 1024L;
        } else if (s.endsWith("KB") || s.endsWith("kb")) {
            s = s.substring(0, s.length() - 2);
            mul = 1024L;
        } else if (s.endsWith("B") || s.endsWith("b")) {
            s = s.substring(0, s.length() - 1);
        }

        if (s.indexOf('.') >= 0) {
            return (long) (mul * Double.parseDouble(s));
        } else {
            return mul * Long.parseLong(s);
        }
    }

    private String colorizeTime(long ts) {
        if (ts >= 1*DAY) {
            return "level100";
        }
        if (ts >= 1*HOUR) {
            return "level75";
        }
        if (ts >= 10*MIN) {
            return "level50";
        }
        if (ts >= 1*MIN) {
            return "level25";
        }
        return null;
    }

    private Chapter genPerPidStats(BugReport br, Node node) {
        Chapter ch = new Chapter(br, "Per-PID Stats");
        TableGen tg = new TableGen(ch, TableGen.FLAG_SORT);
        tg.addColumn("PID", TableGen.FLAG_NONE);
        tg.addColumn("Time", TableGen.FLAG_ALIGN_RIGHT);
        tg.addColumn("Time(ms)", TableGen.FLAG_ALIGN_RIGHT);
        tg.begin();

        for (Node item : node) {
            // item.getLine() has the following format:
            // "PID 147 wake time: +2m37s777ms"
            String f[] = item.getLine().split(" ");

            String sPid = f[1];
            int pid = Integer.parseInt(sPid);
            String proc = sPid;
            ProcessRecord pr = br.getProcessRecord(pid, false, false);
            if (pr != null) {
                proc = pr.getFullName();
            }
            String link = br.createLinkToProcessRecord(pid);
            tg.addData(link, proc, TableGen.FLAG_NONE);

            String sTime = f[4];
            tg.addData(sTime);

            long ts = readTs(sTime);
            tg.addData(Util.shadeValue(ts));
        }
        tg.end();
        return ch;
    }

    private int toX(long ts) {
        return (int)(GRAPH_PX - (ts * GRAPH_PW / mMaxTs));
    }

    private int toY(int level) {
        return GRAPH_PY - level * GRAPH_PH / MAX_LEVEL;
    }

    private void addSignal(long ts, String name, String value) {
        if (name.equals("status")) {
            if (value.equals("charging")) {
                addSignal(ts, "charging", 1);
            } else {
                addSignal(ts, "charging", 0);
            }
        } else if (name.equals("data_conn")) {
            if (value.equals("umts")) {
                addSignal(ts, "edge", 0);
                addSignal(ts, "umts", 1);
                addSignal(ts, "hdspa", 0);
                addSignal(ts, "hspa", 0);
            } else if (value.equals("hspa")) {
                addSignal(ts, "edge", 0);
                addSignal(ts, "umts", 0);
                addSignal(ts, "hdspa", 0);
                addSignal(ts, "hspa", 1);
            } else if (value.equals("hsdpa")) {
                addSignal(ts, "edge", 0);
                addSignal(ts, "umts", 0);
                addSignal(ts, "hdspa", 1);
                addSignal(ts, "hspa", 0);
            } else if (value.equals("edge")) {
                addSignal(ts, "edge", 1);
                addSignal(ts, "umts", 0);
                addSignal(ts, "hdspa", 0);
                addSignal(ts, "hspa", 0);
            } else {
                addSignal(ts, "edge", 0);
                addSignal(ts, "umts", 0);
                addSignal(ts, "hdspa", 0);
                addSignal(ts, "hspa", 0);
            }
        }
    }

    private void addSignal(long ts, String name, int value) {
        int idx = findSignal(name);
        if (idx < 0) return;

        Signal sig = SIGNALS[idx];
        int offY = GRAPH_H + idx * GRAPH_SH;
        int baseY = offY + GRAPH_SH - 2;
        int sigValue = sig.getValue();
        if (value == sigValue) {
            return;
        }
        if (sigValue != -1) {
            int lastX = toX(sig.getTs());
            int x = toX(ts);
            switch (sig.getType()) {
                case Signal.TYPE_BIN:
                    if (lastX == x) {
                        // Signal is rendered too often, draw a gray area instead
                        mG.setColor(COL_SIGNAL_PART);
                        mG.fillRect(x, offY, 1, baseY - offY + 1);
                    } else {
                        mG.setColor(COL_SIGNAL);
                        if (sigValue == 0) {
                            mG.drawLine(lastX + 1, baseY, x, baseY);
                        } else {
                            mG.fillRect(lastX + 1, offY, x - lastX, baseY - offY + 1);
                        }
                    }
                    break;
                case Signal.TYPE_INT:
                    // TODO
                    break;
                case Signal.TYPE_PRC:
                    mG.setColor(COL_SIGNAL);
                    int h = (baseY - offY) * sigValue /100;
                    mG.fillRect(lastX, baseY - h, x - lastX + 1, baseY + 1);
                    break;
            }
        } else {
            // We are setting the first value, let's render the signal name here
            mG.setColor(Color.BLACK);
            mG.drawString(sig.getName(), GRAPH_PX, baseY);
        }
        sig.setValue(ts, value);
    }

    private int findSignal(String s) {
        for (int i = 0; i < SIGNALS.length; i++) {
            if (SIGNALS[i].getName().equals(s)) {
                return i;
            }
        }
        return -1;
    }

    private long readTs(String s) {
        s = Util.strip(s);
        long ret = 0;
        int idx;

        // skip over the negative and positive signs
        if (s.charAt(0) == '-') {
            s = s.substring(1);
        }
        if (s.charAt(0) == '+') {
            s = s.substring(1);
        }

        // Remove the "ms" from the end... it screws up our parsing
        if (s.endsWith("ms")) {
            s = s.substring(0, s.length() - 2);
        }

        // parse day
        idx = s.indexOf("d");
        if (idx >= 0) {
            int day = Integer.parseInt(s.substring(0, idx));
            s = s.substring(idx + 1);
            ret += day * (24 * 3600000L);
        }
        // parse hours
        idx = s.indexOf("h");
        if (idx >= 0) {
            int hour = Integer.parseInt(s.substring(0, idx));
            s = s.substring(idx + 1);
            ret += hour * 3600000L;
        }

        // parse minutes
        idx = s.indexOf("m");
        if (idx >= 0) {
            int min = Integer.parseInt(s.substring(0, idx));
            s = s.substring(idx + 1);
            ret += min * 60000L;
        }

        // parse seconds
        idx = s.indexOf("s");
        if (idx >= 0) {
            int sec = Integer.parseInt(s.substring(0, idx));
            s = s.substring(idx + 1);
            ret += sec * 1000L;
        }

        // parse millis
        int ms = Integer.parseInt(s);
        ret += ms;

        return ret;
    }

}
