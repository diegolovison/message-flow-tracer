package org.jboss.qa.jdg.messageflow.objects;

import org.jboss.qa.jdg.messageflow.Tracer;

import java.util.UUID;

/**
 * @author Radim Vansa &lt;rvansa@redhat.com&gt;
 */
public class Header {
   private final long nanoTime;
   private final long unixTime;

   public Header() {
      this.nanoTime = System.nanoTime();
      this.unixTime = System.currentTimeMillis();
   }

   public Header(long nanoTime, long unixTime) {
      this.nanoTime = nanoTime;
      this.unixTime = unixTime;
   }

   public long getUnixTime() {
      return unixTime;
   }

   public long getNanoTime() {
      return nanoTime;
   }
}
