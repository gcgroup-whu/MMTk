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

import org.mmtk.harness.vm.ObjectModel;
import org.mmtk.plan.Trace;
import org.mmtk.plan.generational.Gen;
import org.mmtk.plan.generational.GenCollector;
import org.mmtk.plan.generational.GenMatureTraceLocal;
import org.mmtk.policy.Space;
import org.mmtk.utility.Log;
import org.mmtk.vm.VM;
import org.vmmagic.pragma.*;
import org.vmmagic.unboxed.ObjectReference;

import java.util.HashMap;

/**
 * This class implements the core functionality for a transitive
 * closure over the heap graph, specifically in a Generational copying
 * collector.
 */
@Uninterruptible
public final class My1GCMatureTraceLocal extends GenMatureTraceLocal {

  /**
   * @param global the global nurseryTrace class to use
   * @param plan the state of the generational collector
   */
  public My1GCMatureTraceLocal(Trace global, GenCollector plan) {
    super(global, plan);
  }

  private static My1GC global() {
    return (My1GC) VM.activePlan.global();
  }

  private int i = 0;

  private HashMap<Integer,Integer> hashMap = new HashMap<>();

  /**
   * Trace a reference into the mature space during GC. This involves
   * determining whether the instance is in from space, and if so,
   * calling the <code>traceObject</code> method of the Copy
   * collector.
   *
   * @param object The object reference to be traced.  This is <i>NOT</i> an
   * interior pointer.
   * @return The possibly moved reference.
   */
  @Override
  public ObjectReference traceObject(ObjectReference object) {
    if (VM.VERIFY_ASSERTIONS) VM.assertions._assert(global().traceFullHeap());
    if (object.isNull()) return object;

//    int i = ObjectModel.getGcHeader(object);
//    i++;
    ObjectModel.setGcHeader(object,i);

    if (Space.isInSpace(My1GC.MS0, object)){
      if(ObjectModel.getGcHeader(object)>1){
        return My1GC.matureSpace0.traceObject(this,object,My1GC.ALLOC_MARKSWEEP);
      }
      return My1GC.matureSpace0.traceObject(this, object, Gen.ALLOC_MATURE_MAJORGC);
    }
    if (Space.isInSpace(My1GC.MS1, object)){
      if(ObjectModel.getGcHeader(object)>1){
        return My1GC.matureSpace1.traceObject(this,object,My1GC.ALLOC_MARKSWEEP);
      }
      return My1GC.matureSpace1.traceObject(this, object, Gen.ALLOC_MATURE_MAJORGC);
    }
    if(Space.isInSpace(My1GC.MSS,object))
      return My1GC.markSweepSpace.traceObject(this, object);
    return super.traceObject(object);
  }

  @Override
  public boolean isLive(ObjectReference object) {
    if (object.isNull()) return false;
    if (Space.isInSpace(My1GC.MS0, object))
      return My1GC.hi ? My1GC.matureSpace0.isLive(object) : true;
    if (Space.isInSpace(My1GC.MS1, object))
      return My1GC.hi ? true : My1GC.matureSpace1.isLive(object);
    if(Space.isInSpace(My1GC.MSS,object))
      return My1GC.markSweepSpace.isLive( object);
    return super.isLive(object);
  }

  /****************************************************************************
   *
   * Object processing and tracing
   */


  /**
   * {@inheritDoc}
   */
  @Override
  public boolean willNotMoveInCurrentCollection(ObjectReference object) {
    if (Space.isInSpace(My1GC.toSpaceDesc(), object)) {
      return false;
    }
    if (Space.isInSpace(My1GC.fromSpaceDesc(), object)) {
      return false;
    }
    if(Space.isInSpace(My1GC.MSS,object)){
      return true;
    }
    return super.willNotMoveInCurrentCollection(object);
  }
}
