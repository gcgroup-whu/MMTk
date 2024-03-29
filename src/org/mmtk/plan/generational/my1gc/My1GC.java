/*
 *  This file is part of the Jikes RVM project (http://jikesrvm.org).
 *
 *  This file is licensed to You under the Eclipse Public License (EPL);
 *  You may not use this file except in compliance with the License. You
 *  may obtain a copy of the License at
 *
 *      http://www.opensource.org/licenses/eclipse-1.0.php
 *
 *  See the COPYRIGHT.txt file distributed with this work for information
 *  regarding copyright ownership.
 */
package org.mmtk.plan.generational.my1gc;

import org.mmtk.plan.StopTheWorld;
import org.mmtk.plan.Trace;
import org.mmtk.plan.TransitiveClosure;
import org.mmtk.plan.generational.Gen;
import org.mmtk.policy.CopySpace;
import org.mmtk.policy.MarkSweepSpace;
import org.mmtk.policy.Space;
import org.mmtk.utility.heap.VMRequest;
import org.mmtk.vm.VM;
import org.vmmagic.pragma.*;

/**
 * This class implements the functionality of a standard
 * two-generation copying collector.  Nursery collections occur when
 * either the heap is full or the nursery is full.  The nursery size
 * is determined by an optional command line argument.  If undefined,
 * the nursery size is "infinite", so nursery collections only occur
 * when the heap is full (this is known as a flexible-sized nursery
 * collector).  Thus both fixed and flexible nursery sizes are
 * supported.  Full heap collections occur when the nursery size has
 * dropped to a statically defined threshold,
 * <code>NURSERY_THRESHOLD</code>.<p>
 *
 * See the Jones &amp; Lins GC book, chapter 7 for a detailed discussion
 * of generational collection and section 7.3 for an overview of the
 * flexible nursery behavior ("The Standard ML of New Jersey
 * collector"), or go to Appel's paper "Simple generational garbage
 * collection and fast allocation." SP&amp;E 19(2):171--183, 1989.<p>
 *
 * All plans make a clear distinction between <i>global</i> and
 * <i>thread-local</i> activities.  Global activities must be
 * synchronized, whereas no synchronization is required for
 * thread-local activities.  Instances of Plan map 1:1 to "kernel
 * threads" (aka CPUs).  Thus instance
 * methods allow fast, unsychronized access to Plan utilities such as
 * allocation and collection.  Each instance rests on static resources
 * (such as memory and virtual memory resources) which are "global"
 * and therefore "static" members of Plan.  This mapping of threads to
 * instances is crucial to understanding the correctness and
 * performance properties of this plan.
 */
@Uninterruptible public class My1GC extends Gen {

  /****************************************************************************
   *
   * Class variables
   */
  public static final int SCAN_MS  = 2;
  public static final int ALLOC_MARKSWEEP = StopTheWorld.ALLOCATORS + 4;
  // GC state

  /**
   * <code>true</code> if copying to "higher" semispace
   */
  static boolean hi = false;

  /**
   * The low half of the copying mature space.  We allocate into this space
   * when <code>hi</code> is <code>false</code>.
   */
  static CopySpace matureSpace0 = new CopySpace("ss0", false, VMRequest.discontiguous());
  static final int MS0 = matureSpace0.getDescriptor();

  /**
   * The high half of the copying mature space. We allocate into this space
   * when <code>hi</code> is <code>true</code>.
   */
  static CopySpace matureSpace1 = new CopySpace("ss1", true, VMRequest.discontiguous());
  static final int MS1 = matureSpace1.getDescriptor();

  public static MarkSweepSpace markSweepSpace = new MarkSweepSpace("ms", VMRequest.discontiguous());
  static final int MSS = markSweepSpace.getDescriptor();


  /****************************************************************************
   *
   * Instance fields
   */

  /**
   *
   */
  final Trace matureTrace;
  //final Trace msTrace;


  /**
   * Constructor
   */
  public My1GC() {
    super();
    if (VM.VERIFY_ASSERTIONS) VM.assertions._assert(!IGNORE_REMSETS); // Not supported for GenCopy
    matureTrace = new Trace(metaDataSpace);
    //msTrace = new Trace(metaDataSpace);
  }

  @Override
  protected boolean copyMature() {
    return true;
  }

  /**
   * @return The semispace we are currently allocating into
   */
  static CopySpace toSpace() {
    return hi ? matureSpace1 : matureSpace0;
  }

  /**
   * @return Space descriptor for to-space.
   */
  static int toSpaceDesc() {
    return hi ? MS1 : MS0;
  }

  /**
   * @return The semispace we are currently copying from
   * (or copied from at last major GC)
   */
  static CopySpace fromSpace() {
    return hi ? matureSpace0 : matureSpace1;
  }

  /**
   * @return Space descriptor for from-space
   */
  static int fromSpaceDesc() {
    return hi ? MS0 : MS1;
  }

  /****************************************************************************
   *
   * Collection
   */

  /**
   * {@inheritDoc}
   */
  @Override
  @Inline
  public void collectionPhase(short phaseId) {
    if (traceFullHeap()) {
      if (phaseId == PREPARE) {
        super.collectionPhase(phaseId);
        hi = !hi; // flip the semi-spaces
        matureSpace0.prepare(hi);
        matureSpace1.prepare(!hi);
        markSweepSpace.prepare(true);
        matureTrace.prepare();
        //msTrace.prepare();
        return;
      }
      if (phaseId == CLOSURE) {
        matureTrace.prepare();
        //msTrace.prepare();
        return;
      }
      if (phaseId == RELEASE) {
        matureTrace.release();
        //msTrace.release();
        markSweepSpace.release();
        fromSpace().release();
        switchNurseryZeroingApproach(fromSpace());
        super.collectionPhase(phaseId);
        return;
      }
    }
    super.collectionPhase(phaseId);
  }

  /*****************************************************************************
   *
   * Accounting
   */

  /**
   * Return the number of pages reserved for use given the pending
   * allocation.
   */
  @Override
  @Inline
  public int getPagesUsed() {
    return toSpace().reservedPages() + super.getPagesUsed();
  }

  /**
   * Return the number of pages reserved for copying.
   *
   * @return the number of pages reserved for copying.
   */
  @Override
  public final int getCollectionReserve() {
    // we must account for the number of pages required for copying,
    // which equals the number of semi-space pages reserved
    return toSpace().reservedPages() + markSweepSpace.reservedPages() + super.getCollectionReserve();
  }

  @Override
  public int getMaturePhysicalPagesAvail() {
    return toSpace().availablePhysicalPages();
  }

  /**************************************************************************
   * Miscellaneous methods
   */

  /**
   * @return The mature space we are currently allocating into
   */
  @Override
  @Inline
  public Space activeMatureSpace() {
    return toSpace();
  }

  @Override
  @Interruptible
  protected void registerSpecializedMethods() {
    TransitiveClosure.registerSpecializedScan(SCAN_MATURE, My1GCMatureTraceLocal.class);
    TransitiveClosure.registerSpecializedScan(SCAN_MS, My1GCMSTraceLocal.class);
    super.registerSpecializedMethods();
  }
}
