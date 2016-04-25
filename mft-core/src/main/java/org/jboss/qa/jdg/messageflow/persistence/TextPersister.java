package org.jboss.qa.jdg.messageflow.persistence;

import org.jboss.qa.jdg.messageflow.objects.Event;
import org.jboss.qa.jdg.messageflow.objects.MessageId;
import org.jboss.qa.jdg.messageflow.objects.Span;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;

/**
 * @author Radim Vansa &lt;rvansa@redhat.com&gt;
 */
public class TextPersister extends Persister {
   public static final String NON_CAUSAL = "NC";
   public static final String SPAN = "SPAN";
   public static final String EVENT = "E";
   private BufferedReader reader;
   private PrintStream printStream = null;
   private String prefix = "";
   private int lineNumber;

   public TextPersister() {}

   public TextPersister(PrintStream printStream, String prefix) {
      this.printStream = printStream;
      this.prefix = prefix;
   }

   public TextPersister(InputStream inputStream) throws IOException {
      reader = new BufferedReader(new InputStreamReader(inputStream));
      String timeSync = reader.readLine();
      if (timeSync == null) {
         System.err.println("Empty file!");
         return;
      }
      String[] nanoAndUnix = timeSync.split("=");
      setTimes(Long.parseLong(nanoAndUnix[0]), Long.parseLong(nanoAndUnix[1]));
      lineNumber = 1;
   }

   @Override
   public void open(String path, long nanoTime, long unixTime) throws IOException {
      printStream = new PrintStream(new BufferedOutputStream(new FileOutputStream(path)));
      printStream.printf("%d=%d\n", nanoTime, unixTime);
   }

   @Override
   public void write(Span span, boolean sort) {
      printStream.print(prefix);
      if (span.isNonCausal()) {
         printStream.print(NON_CAUSAL);
      } else {
         printStream.print(SPAN);
      }
      printStream.print(';');
      if (span.getIncoming() != null) {
         printStream.print(span.getIncoming());
      }
      if (span.getOutcoming() != null) {
         for (MessageId msg : span.getOutcoming()) {
            printStream.print(';');
            printStream.print(msg);
         }
      }
      printStream.println();
      Collection<Span.LocalEvent> events = sort ? span.getSortedEvents() : span.getEvents();
      for (Span.LocalEvent evt : events) {
         printStream.print(prefix);
         printStream.print(EVENT);
         printStream.print(';');
         printStream.print(evt.timestamp);
         printStream.print(';');
         printStream.print(evt.threadName);
         printStream.print(';');
         printStream.print(evt.type);
         printStream.print(';');
         if (evt.payload instanceof List) {
            for (Object item : (List) evt.payload) {
               printStream.print('B');
               printStream.print(item);
               printStream.print(',');
            }
         } else if (evt.payload instanceof MessageId) {
            printStream.print('M');
            printStream.print(evt.payload);
         } else if (evt.payload != null){
            printStream.print('T');
            printStream.print(evt.payload);
         }
         printStream.println();
      }
   }

   @Override
   public void close() {
      if (printStream != null) {
         printStream.close();
      }
   }

   @Override
   public void read(Consumer<Span> spanConsumer, boolean loadEvents) throws IOException {
      String line;
      Span span = null;
      while ((line = reader.readLine()) != null) {
         ++lineNumber;
         if (line.startsWith(EVENT)) {
            if (!loadEvents) continue;
            int index = line.indexOf(';', 2);
            long timestamp = Long.parseLong(line.substring(2, index));
            int start = index + 1;
            index = line.indexOf(';', start);
            String threadName = line.substring(start, index);
            start = index + 1;
            index = line.indexOf(';', start);
            Event.Type type = Event.Type.get(line.substring(start, index));
            String text = line.substring(index + 1).trim();
            Object payload;
            if (text.isEmpty()) {
               payload = null;
            } else if (text.charAt(0) == 'B') {
               ArrayList<MessageId> messages = new ArrayList<>();
               start = 1;
               for (;;) {
                  index = text.indexOf(',', start);
                  if (index < 0) {
                     messages.add(parseMessageId(text, start, text.length()));
                     break;
                  } else {
                     messages.add(parseMessageId(text, start, index));
                     start = index + 1;
                  }
               }
               payload = messages;
            } else if (text.charAt(0) == 'M') {
               payload = parseMessageId(text, 1, text.length());
            } else if (text.charAt(0) == 'T') {
               payload = text.substring(1);
            } else {
               throw new IllegalArgumentException(text);
            }
            span.addEvent(new Span.LocalEvent(timestamp, threadName, type, payload));
         } else {
            if (span != null) {
               spanConsumer.accept(span);
            }
            span = new Span();
            int start;
            if (line.startsWith(SPAN)) {
               start = SPAN.length() + 1;
            } else if (line.startsWith(NON_CAUSAL)) {
               start = NON_CAUSAL.length() + 1;
            } else  {
               throw new IllegalArgumentException("Cannot parse " + line);
            }
            int index = line.indexOf(';', start);
            if (index < 0) index = line.length();
            if (index > start) {
               span.setIncoming(parseMessageId(line, start, index));
            }
            start = index + 1;
            if (start < line.length()) {
               for (; ; ) {
                  index = line.indexOf(';', start);
                  if (index < 0) {
                     span.addOutcoming(parseMessageId(line, start, line.length()));
                     break;
                  } else {
                     span.addOutcoming(parseMessageId(line, start, index));
                     start = index + 1;
                  }
               }
            }
         }
      }
   }

   public MessageId parseMessageId(String text, int fromIndex, int toIndex) {
      int colon = text.indexOf(':', fromIndex);
      return new MessageId.Impl(Short.parseShort(text.substring(fromIndex, colon)), Integer.parseInt(text.substring(colon + 1, toIndex)));
   }

   @Override
   public int getPosition() {
      return lineNumber;
   }
}