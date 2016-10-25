/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.beam.sdk.transforms.reflect;

import com.google.auto.value.AutoValue;
import com.google.common.base.Predicates;
import com.google.common.collect.Iterables;
import com.google.common.reflect.TypeToken;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import org.apache.beam.sdk.coders.Coder;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.DoFn.InputProvider;
import org.apache.beam.sdk.transforms.DoFn.OutputReceiver;
import org.apache.beam.sdk.transforms.DoFn.ProcessContinuation;
import org.apache.beam.sdk.transforms.DoFn.StateId;
import org.apache.beam.sdk.transforms.DoFn.TimerId;
import org.apache.beam.sdk.transforms.reflect.DoFnSignature.Parameter.BoundedWindowParameter;
import org.apache.beam.sdk.transforms.reflect.DoFnSignature.Parameter.StateParameter;
import org.apache.beam.sdk.transforms.splittabledofn.RestrictionTracker;
import org.apache.beam.sdk.transforms.windowing.BoundedWindow;
import org.apache.beam.sdk.util.Timer;
import org.apache.beam.sdk.util.TimerSpec;
import org.apache.beam.sdk.util.state.State;
import org.apache.beam.sdk.util.state.StateSpec;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.TypeDescriptor;

/**
 * Describes the signature of a {@link DoFn}, in particular, which features it uses, which extra
 * context it requires, types of the input and output elements, etc.
 *
 * <p>See <a href="https://s.apache.org/a-new-dofn">A new DoFn</a>.
 */
@AutoValue
public abstract class DoFnSignature {
  /** Class of the original {@link DoFn} from which this signature was produced. */
  public abstract Class<? extends DoFn<?, ?>> fnClass();

  /** Whether this {@link DoFn} does a bounded amount of work per element. */
  public abstract PCollection.IsBounded isBoundedPerElement();

  /** Details about this {@link DoFn}'s {@link DoFn.ProcessElement} method. */
  public abstract ProcessElementMethod processElement();

  /** Details about the state cells that this {@link DoFn} declares. Immutable. */
  public abstract Map<String, StateDeclaration> stateDeclarations();

  /** Details about this {@link DoFn}'s {@link DoFn.StartBundle} method. */
  @Nullable
  public abstract BundleMethod startBundle();

  /** Details about this {@link DoFn}'s {@link DoFn.FinishBundle} method. */
  @Nullable
  public abstract BundleMethod finishBundle();

  /** Details about this {@link DoFn}'s {@link DoFn.Setup} method. */
  @Nullable
  public abstract LifecycleMethod setup();

  /** Details about this {@link DoFn}'s {@link DoFn.Teardown} method. */
  @Nullable
  public abstract LifecycleMethod teardown();

  /** Timer declarations present on the {@link DoFn} class. Immutable. */
  public abstract Map<String, TimerDeclaration> timerDeclarations();

  /** Details about this {@link DoFn}'s {@link DoFn.GetInitialRestriction} method. */
  @Nullable
  public abstract GetInitialRestrictionMethod getInitialRestriction();

  /** Details about this {@link DoFn}'s {@link DoFn.SplitRestriction} method. */
  @Nullable
  public abstract SplitRestrictionMethod splitRestriction();

  /** Details about this {@link DoFn}'s {@link DoFn.GetRestrictionCoder} method. */
  @Nullable
  public abstract GetRestrictionCoderMethod getRestrictionCoder();

  /** Details about this {@link DoFn}'s {@link DoFn.NewTracker} method. */
  @Nullable
  public abstract NewTrackerMethod newTracker();

  /** Details about this {@link DoFn}'s {@link DoFn.OnTimer} methods. */
  @Nullable
  public abstract Map<String, OnTimerMethod> onTimerMethods();

  static Builder builder() {
    return new AutoValue_DoFnSignature.Builder();
  }

  @AutoValue.Builder
  abstract static class Builder {
    abstract Builder setFnClass(Class<? extends DoFn<?, ?>> fnClass);
    abstract Builder setIsBoundedPerElement(PCollection.IsBounded isBounded);
    abstract Builder setProcessElement(ProcessElementMethod processElement);
    abstract Builder setStartBundle(BundleMethod startBundle);
    abstract Builder setFinishBundle(BundleMethod finishBundle);
    abstract Builder setSetup(LifecycleMethod setup);
    abstract Builder setTeardown(LifecycleMethod teardown);
    abstract Builder setGetInitialRestriction(GetInitialRestrictionMethod getInitialRestriction);
    abstract Builder setSplitRestriction(SplitRestrictionMethod splitRestriction);
    abstract Builder setGetRestrictionCoder(GetRestrictionCoderMethod getRestrictionCoder);
    abstract Builder setNewTracker(NewTrackerMethod newTracker);
    abstract Builder setStateDeclarations(Map<String, StateDeclaration> stateDeclarations);
    abstract Builder setTimerDeclarations(Map<String, TimerDeclaration> timerDeclarations);
    abstract Builder setOnTimerMethods(Map<String, OnTimerMethod> onTimerMethods);
    abstract DoFnSignature build();
  }

  /** A method delegated to a annotated method of an underlying {@link DoFn}. */
  public interface DoFnMethod {
    /** The annotated method itself. */
    Method targetMethod();
  }

  /** A descriptor for an optional parameter of the {@link DoFn.ProcessElement} method. */
  public abstract static class Parameter {

    // Private as no extensions other than those nested here are permitted
    private Parameter() {}

    /**
     * Performs case analysis on this {@link Parameter}, processing it with the appropriate
     * {@link Cases#dispatch} case of the provided {@link Cases} object.
     */
    public <ResultT> ResultT match(Cases<ResultT> cases) {
      // This could be done with reflection, but since the number of cases is small and known,
      // they are simply inlined.
      if (this instanceof BoundedWindowParameter) {
        return cases.dispatch((BoundedWindowParameter) this);
      } else if (this instanceof RestrictionTrackerParameter) {
        return cases.dispatch((RestrictionTrackerParameter) this);
      } else if (this instanceof InputProviderParameter) {
        return cases.dispatch((InputProviderParameter) this);
      } else if (this instanceof OutputReceiverParameter) {
        return cases.dispatch((OutputReceiverParameter) this);
      } else if (this instanceof StateParameter) {
        return cases.dispatch((StateParameter) this);
      } else if (this instanceof TimerParameter) {
        return cases.dispatch((TimerParameter) this);
      } else {
        throw new IllegalStateException(
            String.format("Attempt to case match on unknown %s subclass %s",
                Parameter.class.getCanonicalName(), this.getClass().getCanonicalName()));
      }
    }

    /**
     * An interface for destructuring a {@link Parameter}.
     */
    public interface Cases<ResultT> {
      ResultT dispatch(BoundedWindowParameter p);
      ResultT dispatch(InputProviderParameter p);
      ResultT dispatch(OutputReceiverParameter p);
      ResultT dispatch(RestrictionTrackerParameter p);
      ResultT dispatch(StateParameter p);
      ResultT dispatch(TimerParameter p);

      /**
       * A base class for a visitor with a default method for cases it is not interested in.
       */
      abstract class WithDefault<ResultT> implements Cases<ResultT> {

        protected abstract ResultT dispatchDefault(Parameter p);

        @Override
        public ResultT dispatch(BoundedWindowParameter p) {
          return dispatchDefault(p);
        }

        @Override
        public ResultT dispatch(InputProviderParameter p) {
          return dispatchDefault(p);
        }

        @Override
        public ResultT dispatch(OutputReceiverParameter p) {
          return dispatchDefault(p);
        }

        @Override
        public ResultT dispatch(RestrictionTrackerParameter p) {
          return dispatchDefault(p);
        }

        @Override
        public ResultT dispatch(StateParameter p) {
          return dispatchDefault(p);
        }

        @Override
        public ResultT dispatch(TimerParameter p) {
          return dispatchDefault(p);
        }
      }
    }

    // These parameter descriptors are constant
    private static final BoundedWindowParameter BOUNDED_WINDOW_PARAMETER =
        new AutoValue_DoFnSignature_Parameter_BoundedWindowParameter();
    private static final RestrictionTrackerParameter RESTRICTION_TRACKER_PARAMETER =
        new AutoValue_DoFnSignature_Parameter_RestrictionTrackerParameter();
    private static final InputProviderParameter INPUT_PROVIDER_PARAMETER =
        new AutoValue_DoFnSignature_Parameter_InputProviderParameter();
    private static final OutputReceiverParameter OUTPUT_RECEIVER_PARAMETER =
        new AutoValue_DoFnSignature_Parameter_OutputReceiverParameter();

    /**
     * Returns a {@link BoundedWindowParameter}.
     */
    public static BoundedWindowParameter boundedWindow() {
      return BOUNDED_WINDOW_PARAMETER;
    }

    /**
     * Returns an {@link InputProviderParameter}.
     */
    public static InputProviderParameter inputProvider() {
      return INPUT_PROVIDER_PARAMETER;
    }

    /**
     * Returns an {@link OutputReceiverParameter}.
     */
    public static OutputReceiverParameter outputReceiver() {
      return OUTPUT_RECEIVER_PARAMETER;
    }

    /**
     * Returns a {@link RestrictionTrackerParameter}.
     */
    public static RestrictionTrackerParameter restrictionTracker() {
      return RESTRICTION_TRACKER_PARAMETER;
    }

    /**
     * Returns a {@link StateParameter} referring to the given {@link StateDeclaration}.
     */
    public static StateParameter stateParameter(StateDeclaration decl) {
      return new AutoValue_DoFnSignature_Parameter_StateParameter(decl);
    }

    public static TimerParameter timerParameter(TimerDeclaration decl) {
      return new AutoValue_DoFnSignature_Parameter_TimerParameter(decl);
    }

    /**
     * Descriptor for a {@link Parameter} of type {@link BoundedWindow}.
     *
     * <p>All such descriptors are equal.
     */
    @AutoValue
    public abstract static class BoundedWindowParameter extends Parameter {
      BoundedWindowParameter() {}
    }

    /**
     * Descriptor for a {@link Parameter} of type {@link InputProvider}.
     *
     * <p>All such descriptors are equal.
     */
    @AutoValue
    public abstract static class InputProviderParameter extends Parameter {
      InputProviderParameter() {}
    }

    /**
     * Descriptor for a {@link Parameter} of type {@link OutputReceiver}.
     *
     * <p>All such descriptors are equal.
     */
    @AutoValue
    public abstract static class OutputReceiverParameter extends Parameter {
      OutputReceiverParameter() {}
    }

    /**
     * Descriptor for a {@link Parameter} of a subclass of {@link RestrictionTracker}.
     *
     * <p>All such descriptors are equal.
     */
    @AutoValue
    public abstract static class RestrictionTrackerParameter extends Parameter {
      // Package visible for AutoValue
      RestrictionTrackerParameter() {}
    }

    /**
     * Descriptor for a {@link Parameter} of a subclass of {@link State}, with an id indicated by
     * its {@link StateId} annotation.
     *
     * <p>All descriptors for the same declared state are equal.
     */
    @AutoValue
    public abstract static class StateParameter extends Parameter {
      // Package visible for AutoValue
      StateParameter() {}
      public abstract StateDeclaration referent();
    }

    /**
     * Descriptor for a {@link Parameter} of type {@link Timer}, with an id indicated by
     * its {@link TimerId} annotation.
     */
    @AutoValue
    public abstract static class TimerParameter extends Parameter {
      // Package visible for AutoValue
      TimerParameter() {}
      public abstract TimerDeclaration referent();
    }
  }

  /** Describes a {@link DoFn.ProcessElement} method. */
  @AutoValue
  public abstract static class ProcessElementMethod implements DoFnMethod {
    /** The annotated method itself. */
    @Override
    public abstract Method targetMethod();

    /** Types of optional parameters of the annotated method, in the order they appear. */
    public abstract List<Parameter> extraParameters();

    /** Concrete type of the {@link RestrictionTracker} parameter, if present. */
    @Nullable
    abstract TypeToken<?> trackerT();

    /** Whether this {@link DoFn} returns a {@link ProcessContinuation} or void. */
    public abstract boolean hasReturnValue();

    static ProcessElementMethod create(
        Method targetMethod,
        List<Parameter> extraParameters,
        TypeToken<?> trackerT,
        boolean hasReturnValue) {
      return new AutoValue_DoFnSignature_ProcessElementMethod(
          targetMethod, Collections.unmodifiableList(extraParameters), trackerT, hasReturnValue);
    }

    /**
     * Whether this {@link DoFn} observes - directly or indirectly - the window that an element
     * resides in.
     *
     * <p>{@link State} and {@link Timer} parameters indirectly observe the window, because
     * they are each scoped to a single window.
     */
    public boolean observesWindow() {
      return Iterables.any(
          extraParameters(),
          Predicates.or(
              Predicates.instanceOf(BoundedWindowParameter.class),
              Predicates.instanceOf(StateParameter.class)));
    }

    /**
     * Whether this {@link DoFn} is <a href="https://s.apache.org/splittable-do-fn">splittable</a>.
     */
    public boolean isSplittable() {
      return extraParameters().contains(Parameter.restrictionTracker());
    }
  }

  /** Describes a {@link DoFn.OnTimer} method. */
  @AutoValue
  public abstract static class OnTimerMethod implements DoFnMethod {

    /** The id on the method's {@link DoFn.TimerId} annotation. */
    public abstract String id();

    /** The annotated method itself. */
    @Override
    public abstract Method targetMethod();

    /** Types of optional parameters of the annotated method, in the order they appear. */
    public abstract List<Parameter> extraParameters();

    static OnTimerMethod create(Method targetMethod, String id, List<Parameter> extraParameters) {
      return new AutoValue_DoFnSignature_OnTimerMethod(
          id, targetMethod, Collections.unmodifiableList(extraParameters));
    }
  }

  /**
   * Describes a timer declaration; a field of type {@link TimerSpec} annotated with
   * {@DoFn.TimerId}.
   */
  @AutoValue
  public abstract static class TimerDeclaration {
    public abstract String id();
    public abstract Field field();

    static TimerDeclaration create(String id, Field field) {
      return new AutoValue_DoFnSignature_TimerDeclaration(id, field);
    }
  }


  /** Describes a {@link DoFn.StartBundle} or {@link DoFn.FinishBundle} method. */
  @AutoValue
  public abstract static class BundleMethod implements DoFnMethod {
    /** The annotated method itself. */
    @Override
    public abstract Method targetMethod();

    static BundleMethod create(Method targetMethod) {
      return new AutoValue_DoFnSignature_BundleMethod(targetMethod);
    }
  }

  /**
   * Describes a state declaration; a field of type {@link StateSpec} annotated with
   * {@link DoFn.StateId}.
   */
  @AutoValue
  public abstract static class StateDeclaration {
    public abstract String id();
    public abstract Field field();
    public abstract TypeDescriptor<? extends State> stateType();

    static StateDeclaration create(
        String id, Field field, TypeDescriptor<? extends State> stateType) {
      return new AutoValue_DoFnSignature_StateDeclaration(id, field, stateType);
    }
  }

  /** Describes a {@link DoFn.Setup} or {@link DoFn.Teardown} method. */
  @AutoValue
  public abstract static class LifecycleMethod implements DoFnMethod {
    /** The annotated method itself. */
    @Override
    public abstract Method targetMethod();

    static LifecycleMethod create(Method targetMethod) {
      return new AutoValue_DoFnSignature_LifecycleMethod(targetMethod);
    }
  }

  /** Describes a {@link DoFn.GetInitialRestriction} method. */
  @AutoValue
  public abstract static class GetInitialRestrictionMethod implements DoFnMethod {
    /** The annotated method itself. */
    @Override
    public abstract Method targetMethod();

    /** Type of the returned restriction. */
    abstract TypeToken<?> restrictionT();

    static GetInitialRestrictionMethod create(Method targetMethod, TypeToken<?> restrictionT) {
      return new AutoValue_DoFnSignature_GetInitialRestrictionMethod(targetMethod, restrictionT);
    }
  }

  /** Describes a {@link DoFn.SplitRestriction} method. */
  @AutoValue
  public abstract static class SplitRestrictionMethod implements DoFnMethod {
    /** The annotated method itself. */
    @Override
    public abstract Method targetMethod();

    /** Type of the restriction taken and returned. */
    abstract TypeToken<?> restrictionT();

    static SplitRestrictionMethod create(Method targetMethod, TypeToken<?> restrictionT) {
      return new AutoValue_DoFnSignature_SplitRestrictionMethod(targetMethod, restrictionT);
    }
  }

  /** Describes a {@link DoFn.NewTracker} method. */
  @AutoValue
  public abstract static class NewTrackerMethod implements DoFnMethod {
    /** The annotated method itself. */
    @Override
    public abstract Method targetMethod();

    /** Type of the input restriction. */
    abstract TypeToken<?> restrictionT();

    /** Type of the returned {@link RestrictionTracker}. */
    abstract TypeToken<?> trackerT();

    static NewTrackerMethod create(
        Method targetMethod, TypeToken<?> restrictionT, TypeToken<?> trackerT) {
      return new AutoValue_DoFnSignature_NewTrackerMethod(targetMethod, restrictionT, trackerT);
    }
  }

  /** Describes a {@link DoFn.GetRestrictionCoder} method. */
  @AutoValue
  public abstract static class GetRestrictionCoderMethod implements DoFnMethod {
    /** The annotated method itself. */
    @Override
    public abstract Method targetMethod();

    /** Type of the returned {@link Coder}. */
    abstract TypeToken<?> coderT();

    static GetRestrictionCoderMethod create(Method targetMethod, TypeToken<?> coderT) {
      return new AutoValue_DoFnSignature_GetRestrictionCoderMethod(targetMethod, coderT);
    }
  }
}