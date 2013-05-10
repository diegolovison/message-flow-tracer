/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2013, Red Hat Middleware LLC, and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.jboss.qa.jdg.messageflow;

import java.io.PrintStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * @author Radim Vansa &lt;rvansa@redhat.com&gt;
 */
public class AnalyseTraces implements Processor {

   private static final String NO_TAG = "-no-tag-";
   private Map<String, TraceStats> traceStats = new TreeMap<String, TraceStats>();
   private PrintStream out = System.out;

   private static class TraceStats {
      final String tag;
      TreeMap<String, Integer> msgOccurrences = new TreeMap<String, Integer>();
      int traces;
      int sumNodes;

      int sumMessages;
      int minMessages = Integer.MAX_VALUE;
      int maxMessages;

      int sumThreads;
      int minThreads = Integer.MAX_VALUE;
      int maxThreads;

      long sumNanoTime;
      long minNanoTime = Long.MAX_VALUE;
      long maxNanoTime;

      long sumWallTime;
      long minWallTime = Long.MAX_VALUE;
      long maxWallTime;

      private TraceStats(String tag) {
         this.tag = tag;
      }
   }

   @Override
   public void init(Composer composer) {
   }

   @Override
   public void process(Trace trace) {
      String traceTag = NO_TAG;
      for (Event event : trace.events) {
         if (event.type == Event.Type.TRACE_TAG) {
            traceTag = event.text;
            break;
         }
      }
      TraceStats stats = traceStats.get(traceTag);
      if (stats == null) {
         stats = new TraceStats(traceTag);
         traceStats.put(traceTag, stats);
      }
      stats.minMessages = Math.min(stats.minMessages, trace.messages.size());
      stats.maxMessages = Math.max(stats.maxMessages, trace.messages.size());
      stats.sumMessages += trace.messages.size();
      stats.traces++;
      for (String message : trace.messages) {
         TreeSet<String> msgTags = new TreeSet<String>();
         for (Event event : trace.events) {
            if (event.type == Event.Type.HANDLING && event.text.equals(message)) {
               for (Event e : trace.events) {
                  if (e.type == Event.Type.MESSAGE_TAG && e.source == event.source && e.span == event.span) {
                     msgTags.add(e.text);
                  }
               }
            }
         }
         String msgTag;
         if (msgTags.isEmpty()) {
            msgTag = NO_TAG;
         } else {
            StringBuilder allTags = new StringBuilder();
            for (Iterator<String> iterator = msgTags.iterator(); iterator.hasNext(); ) {
               allTags.append(iterator.next());
               if (iterator.hasNext()) allTags.append(", ");
            }
            msgTag = allTags.toString();
         }
         Integer occurrences = stats.msgOccurrences.get(msgTag);
         if (occurrences == null) {
            stats.msgOccurrences.put(msgTag, 1);
         } else {
            stats.msgOccurrences.put(msgTag, occurrences + 1);
         }
      }
      Map<String, Long> startTimestamps = new HashMap<String, Long>();
      Map<String, Long> lastTimestamps = new HashMap<String, Long>();
      int threads = 0;
      long nanoTime = 0;
      Set<String> nodes = new HashSet<String>();
      for (Event e : trace.events) {
         nodes.add(e.source);
         String nodeThread = e.source + "|" + e.threadName;
         Long timestamp = startTimestamps.get(nodeThread);
         if (timestamp == null) {
            startTimestamps.put(nodeThread, e.nanoTime);
         }
         if (e.type == Event.Type.THREAD_PROCESSING_COMPLETE) {
            Long start = startTimestamps.remove(nodeThread);
            if (start != null) {
               nanoTime += e.nanoTime - start;
               threads++;
            }
         }
         lastTimestamps.put(nodeThread, e.nanoTime);
      }
      for (Map.Entry<String, Long> entry : startTimestamps.entrySet()) {
         Long last = lastTimestamps.get(entry.getKey());
         if (last != null) {
            nanoTime += last - entry.getValue();
            threads++;
         }
      }
      stats.sumNodes += nodes.size();
      stats.sumNanoTime += nanoTime;
      stats.minNanoTime = Math.min(nanoTime, stats.minNanoTime);
      stats.maxNanoTime = Math.max(nanoTime, stats.maxNanoTime);
      long wallTime = trace.events.last().timestamp.getTime() - trace.events.first().timestamp.getTime();
      stats.sumWallTime += wallTime;
      stats.minWallTime = Math.min(wallTime, stats.minWallTime);
      stats.maxWallTime = Math.max(wallTime, stats.maxWallTime);
      stats.sumThreads += threads;
      stats.minThreads = Math.min(threads, stats.minThreads);
      stats.maxThreads = Math.max(threads, stats.maxThreads);

   }

   @Override
   public void finish() {
      out.println("Traces statistics");
      for (TraceStats stats : traceStats.values()) {
         out.printf("%s:\t%d traces, avg %1.2f msg (%d - %d)\n", stats.tag, stats.traces,
                    (double) stats.sumMessages / stats.traces, stats.minMessages, stats.maxMessages);
         for (Map.Entry<String, Integer> message : stats.msgOccurrences.entrySet()) {
            out.printf("\t%1.2fx: %s\n", (double) message.getValue() / stats.traces, message.getKey());
         }
         out.printf("\tavg %1.2f nodes, avg %2.2f threads (%d - %d)\n", (double) stats.sumNodes / stats.traces,
                    (double) stats.sumThreads / stats.traces, stats.minThreads, stats.maxThreads);
         out.printf("\tProcessing took avg %.2f us (%.2f - %.2f)\n",
                    (double) stats.sumNanoTime / (stats.traces * 1000d), stats.minNanoTime / 1000d, stats.maxNanoTime / 1000d);
         out.printf("\tWall time delta %d ms (%d - %d)\n\n",  stats.sumWallTime / stats.traces, stats.minWallTime, stats.maxWallTime);
      }
   }
}
