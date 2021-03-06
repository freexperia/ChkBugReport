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
package com.sonyericsson.chkbugreport.traceview;

import com.sonyericsson.chkbugreport.Bug;
import com.sonyericsson.chkbugreport.Chapter;
import com.sonyericsson.chkbugreport.Plugin;
import com.sonyericsson.chkbugreport.Report;
import com.sonyericsson.chkbugreport.traceview.TraceReport.MethodRun;
import com.sonyericsson.chkbugreport.traceview.TraceReport.ThreadInfo;

import java.util.Vector;

/**
 * This plugin analyzes the activity on the main loop and tries to detect bad behaviour.
 */
public class MainLooplugin extends Plugin {

    /* These values might need some adjustments */
    public static final int MAX_TIME_DRAW = 40;
    public static final int MAX_TIME_MEASURE = 50;
    public static final int MAX_TIME_LAYOUT = 100;

    private static final int MAX_DRAW_LATENCY = 100; // 100ms

    private static final String SIG_MEASURE = "android/view/View.measure";
    private static final String SIG_LAYOUT = "android/view/View.layout";
    private static final String SIG_DRAW = "android/view/ViewRoot.draw";
    private static final String SIG_INVALIDATE = "android/view/View.invalidate";
    private static final String SIG_ON_CREATE = "android/app/Instrumentation.callActivityOnCreate";
    private static final String SIG_ON_DESTROY = "android/app/Instrumentation.callActivityOnDestroy";
    private static final String SIG_ON_RESTORE_INSTANCE_STATE = "android/app/Instrumentation.callActivityOnRestoreInstanceState";
    private static final String SIG_ON_POST_CREATE = "android/app/Instrumentation.callActivityOnPostCreate";
    private static final String SIG_ON_NEW_INTENT = "android/app/Instrumentation.callActivityOnNewIntent";
    private static final String SIG_ON_START = "android/app/Instrumentation.callActivityOnStart";
    private static final String SIG_ON_RESTART = "android/app/Instrumentation.callActivityOnRestart";
    private static final String SIG_ON_RESUME = "android/app/Instrumentation.callActivityOnResume";
    private static final String SIG_ON_STOP = "android/app/Instrumentation.callActivityOnStop";
    private static final String SIG_ON_SAVE_INSTANCE_STATE = "android/app/Instrumentation.callActivityOnSaveInstanceState";
    private static final String SIG_ON_PAUSE = "android/app/Instrumentation.callActivityOnPause";
    private static final String SIG_ON_USER_LEAVING = "android/app/Instrumentation.callActivityOnUserLeaving";

    private static final String SIGS_MLD[] = {
        SIG_MEASURE,
        SIG_LAYOUT,
        SIG_DRAW,
        SIG_INVALIDATE,

        SIG_ON_CREATE,
        SIG_ON_DESTROY,
        SIG_ON_RESTORE_INSTANCE_STATE,
        SIG_ON_POST_CREATE,
        SIG_ON_NEW_INTENT,
        SIG_ON_START,
        SIG_ON_RESTART,
        SIG_ON_RESUME,
        SIG_ON_STOP,
        SIG_ON_SAVE_INSTANCE_STATE,
        SIG_ON_PAUSE,
        SIG_ON_USER_LEAVING,
    };

    private static class SlowRun {
        MethodRun run;
        int id;
        int duration;
        int target;

        public SlowRun(MethodRun run, int id, int dur, int target) {
            this.run = run;
            this.id = id;
            this.duration = dur;
            this.target = target;
        }
    }

    private static class DelayedDraw {
        int id;
        int delay;
        int target;

        public DelayedDraw(MethodRun run, int id, int delay, int target) {
            this.id = id;
            this.delay = delay;
            this.target = target;
        }
    }

    @Override
    public int getPrio() {
        return 80;
    }

    @Override
    public void load(Report br) {
        // NOP
    }

    @Override
    public void generate(Report br) {
        TraceReport rep = (TraceReport)br;

        // First of all, find the main loop
        ThreadInfo thread = rep.findThread(1);

        // Now run a few analyzers on it
        checkLayoutAndDraw(rep, thread);
    }

    /**
     * This method is checked if draw is called soon enough after a layout.
     * It also checks how much time is spent in draw/layout/measure, and if it's above
     * a given threshold, report it.
     *
     * @param rep The report object
     * @param thread The main thread
     */
    private void checkLayoutAndDraw(TraceReport rep, ThreadInfo thread) {
        // First, collect all calls to measure, layout and draw
        Vector<MethodRun> runs = findMethodRuns(rep, thread, SIGS_MLD, true);

        // The collection of method runs which take too long time
        Vector<SlowRun> slowRuns = new Vector<SlowRun>();

        // The collection of draw method calls which are delayed
        Vector<DelayedDraw> delayedDraws = new Vector<DelayedDraw>();

        // Create chapter
        Chapter ch = new Chapter(rep, "Main thread activity");
        rep.addChapter(ch);
        ch.addLine("<div class=\"main-thread-activity\">");
        ch.addLine("<div class=\"main-thread-activity-head\">");
        ch.addLine("<div>Here are some important method calls from the main thread:</div>");
        ch.addLine("<div>(Note: thread local times are used, since here we are not interested in the effect of other threads)</div>");
        ch.addLine("</div>"); //  class=main-thread-activity-head
        ch.addLine("<div class=\"main-thread-activity-body\">");

        // Now process the list
        int pendingLayout = -1;
        int pendingInvalidate = -1;
        int id = 0;
        for (MethodRun run : runs) {
            id++;
            String name = run.shortName;
            int dur = (run.endLocalTime - run.startLocalTime) / 1000; // us -> ms
            if (SIG_MEASURE.equals(name)) {
                String col = "";
                if (dur > MAX_TIME_MEASURE) {
                    slowRuns.add(new SlowRun(run, id, dur, MAX_TIME_MEASURE));
                    col = "mta-red";
                }
                addMTAItem(ch, id, "MEASURE", col, run);
            } else if (SIG_LAYOUT.equals(name)) {
                if (pendingLayout == -1) {
                    pendingLayout = run.endLocalTime;
                }
                String col = "";
                if (dur > MAX_TIME_LAYOUT) {
                    slowRuns.add(new SlowRun(run, id, dur, MAX_TIME_LAYOUT));
                    col = "mta-red";
                }
                addMTAItem(ch, id, "LAYOUT", col, run);
            } else if (SIG_DRAW.equals(name)) {
                String col = "";
                if (dur > MAX_TIME_DRAW) {
                    slowRuns.add(new SlowRun(run, id, dur, MAX_TIME_DRAW));
                    col = "mta-red";
                }
                addMTAItem(ch, id, "DRAW", col, run);
                // Check how much time has elapsed since a layout or invalidate
                int now = run.startLocalTime; // Let's use the start time for latency, since if draw is slow, that's handled separately
                if (pendingInvalidate != -1) {
                    int delay = (now - pendingInvalidate) / 1000;
                    if (delay > MAX_DRAW_LATENCY) {
                        addMTANote(ch, "High latency! Delay from invalidate: " + delay + "ms (should be below " + MAX_DRAW_LATENCY + "ms)");
                        delayedDraws.add(new DelayedDraw(run, id, delay, MAX_DRAW_LATENCY));
                    }
                }
                if (pendingLayout != -1) {
                    int delay = (now - pendingLayout) / 1000;
                    if (delay > MAX_DRAW_LATENCY) {
                        addMTANote(ch, "High latency! Delay from layout: " + delay + "ms (should be below " + MAX_DRAW_LATENCY + "ms)");
                        delayedDraws.add(new DelayedDraw(run, id, delay, MAX_DRAW_LATENCY));
                    }
                }
                // Reset
                pendingInvalidate = pendingLayout = -1;
            } else if (SIG_INVALIDATE.equals(name)) {
                if (pendingInvalidate == -1) {
                    pendingInvalidate = run.endLocalTime;
                    addMTAItem(ch, id, "INVALIDATE (first)", "", run);
                }
            } else if (SIG_ON_CREATE.equals(name)) {
                addMTAItem(ch, id, "onCreate", "", run);
            } else if (SIG_ON_DESTROY.equals(name)) {
                addMTAItem(ch, id, "onDestroy", "", run);
            } else if (SIG_ON_NEW_INTENT.equals(name)) {
                addMTAItem(ch, id, "onNewIntent", "", run);
            } else if (SIG_ON_PAUSE.equals(name)) {
                addMTAItem(ch, id, "onPause", "", run);
            } else if (SIG_ON_POST_CREATE.equals(name)) {
                addMTAItem(ch, id, "onPostCreate", "", run);
            } else if (SIG_ON_RESTART.equals(name)) {
                addMTAItem(ch, id, "onRestart", "", run);
            } else if (SIG_ON_RESTORE_INSTANCE_STATE.equals(name)) {
                addMTAItem(ch, id, "onRestoreInstanceState", "", run);
            } else if (SIG_ON_RESUME.equals(name)) {
                addMTAItem(ch, id, "onResume", "", run);
            } else if (SIG_ON_SAVE_INSTANCE_STATE.equals(name)) {
                addMTAItem(ch, id, "onSaveInstanceState", "", run);
            } else if (SIG_ON_START.equals(name)) {
                addMTAItem(ch, id, "onStart", "", run);
            } else if (SIG_ON_STOP.equals(name)) {
                addMTAItem(ch, id, "onStop", "", run);
            } else if (SIG_ON_USER_LEAVING.equals(name)) {
                addMTAItem(ch, id, "onUserLeaving", "", run);
            } else {
                addMTAItem(ch, id, name, "", run);
            }
        }

        // We should check the delay of the draw even if the draw is missing
        int now = getThreadDuration(thread);
        if (pendingInvalidate != -1) {
            int delay = (now - pendingInvalidate) / 1000;
            if (delay > MAX_DRAW_LATENCY) {
                addMTANote(ch, "Missing draw with high latency! Delay from invalidate: " + delay + "ms (should be below " + MAX_DRAW_LATENCY + "ms)");
                delayedDraws.add(new DelayedDraw(null, id, delay, MAX_DRAW_LATENCY));
            }
        }
        if (pendingLayout != -1) {
            int delay = (now - pendingLayout) / 1000;
            if (delay > MAX_DRAW_LATENCY) {
                addMTANote(ch, "Missing draw with high latency! Delay from layout: " + delay + "ms (should be below " + MAX_DRAW_LATENCY + "ms)");
                delayedDraws.add(new DelayedDraw(null, id, delay, MAX_DRAW_LATENCY));
            }
        }

        // Create an error report from the slow items
        if (slowRuns.size() > 0) {
            Bug bug = new Bug(Bug.PRIO_TRACEVIEW_SLOW_METHOD, 0, "Slow methods on main loop");
            rep.addBug(bug);

            bug.addLine("<p>The following method calls on the main thread seems to take longer time than expected:</p>");
            bug.addLine("<ul>");
            for (SlowRun run : slowRuns) {
                bug.addLine("<li><a href=\"#mta-item-" + run.id + "\">" + run.run.shortName + "</a> (duration: " + run.duration + "ms, expected below " + run.target + "ms)</li>");
            }
            bug.addLine("</ul>");
        }

        // Create an error report from the delayed draw items
        if (slowRuns.size() > 0) {
            Bug bug = new Bug(Bug.PRIO_TRACEVIEW_DELAYED_DRAW, 0, "Delayed draw calls");
            rep.addBug(bug);

            bug.addLine("<p>The following draw method calls on the main thread seems to come too late after either invalidate or layout:</p>");
            bug.addLine("<ul>");
            int lastId = -1;
            for (DelayedDraw dd : delayedDraws) {
                if (dd.id == lastId) continue; // skip duplicates
                lastId = dd.id;
                bug.addLine("<li><a href=\"#mta-item-" + dd.id + "\">" + SIG_DRAW + "</a> (delay: " + dd.delay + "ms, expected below " + dd.target + "ms)</li>");
            }
            bug.addLine("</ul>");
        }

        ch.addLine("</div>"); //  class=main-thread-activity-body
        ch.addLine("</div>"); //  class=main-thread-activity
    }

    private int getThreadDuration(ThreadInfo thread) {
        int cnt = thread.calls.size();
        if (cnt == 0) return 0;
        return thread.calls.get(cnt - 1).endLocalTime;
    }

    private void addMTANote(Chapter ch, String msg) {
        ch.addLine("<div class=\"mta-note %s\">" + msg + "</div>");
    }

    private void addMTAItem(Chapter ch, int id, String string, String col, MethodRun run) {
        ch.addLine(String.format("<div class=\"mta-item %s\"><a name=\"mta-item-%d\">[@%5d +%4dms] %s</a></div>",
                col, id,
                run.startLocalTime / 1000, (run.endLocalTime-run.startLocalTime) / 1000,
                string));
    }

    private Vector<MethodRun> findMethodRuns(TraceReport rep, ThreadInfo thread, String[] sigs, boolean matchShortName) {
        Vector<MethodRun> runs = new Vector<TraceReport.MethodRun>();
        for (MethodRun run : thread.calls) {
            findMethodRuns(rep, runs, run, sigs, matchShortName);
        }
        return runs;
    }

    private void findMethodRuns(TraceReport rep, Vector<MethodRun> runs, MethodRun run,
            String[] sigs, boolean matchShortName)
    {
        String name = matchShortName ? run.shortName : run.name;
        for (String s : sigs) {
            if (name.equals(s)) {
                // Found it! Add to the list, and exit
                runs.add(run);
                return;
            }
        }

        // If not found, try the children
        for (MethodRun child : run.calls) {
            findMethodRuns(rep, runs, child, sigs, matchShortName);
        }
    }

}
