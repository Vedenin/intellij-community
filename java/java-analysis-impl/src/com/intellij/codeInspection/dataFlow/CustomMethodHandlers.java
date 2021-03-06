/*
 * Copyright 2000-2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.codeInspection.dataFlow;

import com.intellij.codeInspection.dataFlow.rangeSet.LongRangeSet;
import com.intellij.codeInspection.dataFlow.value.*;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.PsiType;
import com.intellij.util.ArrayUtil;
import com.intellij.util.ObjectUtils;
import com.siyeh.ig.callMatcher.CallMapper;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static com.intellij.psi.CommonClassNames.*;
import static com.intellij.psi.JavaTokenType.*;
import static com.siyeh.ig.callMatcher.CallMatcher.*;

/**
 * @author Tagir Valeev
 */
public class CustomMethodHandlers {
  interface CustomMethodHandler {
    List<DfaMemoryState> handle(DfaValue qualifier, DfaValue[] args, DfaMemoryState memState, DfaValueFactory factory);
  }

  private static final CallMapper<CustomMethodHandler> CUSTOM_METHOD_HANDLERS = new CallMapper<CustomMethodHandler>()
    .register(instanceCall(JAVA_LANG_STRING, "isEmpty").parameterCount(0),
              (qualifier, args, memState, factory) -> stringIsEmpty(qualifier, memState, factory))
    .register(instanceCall(JAVA_LANG_STRING, "indexOf", "lastIndexOf"),
              (qualifier, args, memState, factory) -> stringIndexOf(qualifier, memState, factory))
    .register(instanceCall(JAVA_LANG_STRING, "equals").parameterCount(1),
              (qualifier, args, memState, factory) -> stringEquals(qualifier, args, memState, factory, false))
    .register(instanceCall(JAVA_LANG_STRING, "equalsIgnoreCase").parameterCount(1),
              (qualifier, args, memState, factory) -> stringEquals(qualifier, args, memState, factory, true))
    .register(instanceCall(JAVA_LANG_STRING, "startsWith").parameterCount(1),
              (qualifier, args, memState, factory) -> stringStartsEnds(qualifier, args, memState, factory, false))
    .register(instanceCall(JAVA_LANG_STRING, "endsWith").parameterCount(1),
              (qualifier, args, memState, factory) -> stringStartsEnds(qualifier, args, memState, factory, true))
    .register(anyOf(staticCall(JAVA_LANG_MATH, "max").parameterTypes("int", "int"),
                    staticCall(JAVA_LANG_MATH, "max").parameterTypes("long", "long"),
                    staticCall(JAVA_LANG_INTEGER, "max").parameterTypes("int", "int"),
                    staticCall(JAVA_LANG_LONG, "max").parameterTypes("long", "long")),
              (qualifier, args, memState, factory) -> mathMinMax(args, memState, factory, true))
    .register(anyOf(staticCall(JAVA_LANG_MATH, "min").parameterTypes("int", "int"),
                    staticCall(JAVA_LANG_MATH, "min").parameterTypes("long", "long"),
                    staticCall(JAVA_LANG_INTEGER, "min").parameterTypes("int", "int"),
                    staticCall(JAVA_LANG_LONG, "min").parameterTypes("long", "long")),
              (qualifier, args, memState, factory) -> mathMinMax(args, memState, factory, false))
    .register(staticCall(JAVA_LANG_MATH, "abs").parameterTypes("int"),
              (qualifier, args, memState, factory) -> mathAbs(args, memState, factory, false))
    .register(staticCall(JAVA_LANG_MATH, "abs").parameterTypes("long"),
              (qualifier, args, memState, factory) -> mathAbs(args, memState, factory, true));

  public static CustomMethodHandler find(PsiMethodCallExpression call) {
    return CUSTOM_METHOD_HANDLERS.mapFirst(call);
  }

  private static List<DfaMemoryState> stringStartsEnds(DfaValue qualifier,
                                                       DfaValue[] args,
                                                       DfaMemoryState memState,
                                                       DfaValueFactory factory,
                                                       boolean ends) {
    DfaValue arg = ArrayUtil.getFirstElement(args);
    if (arg == null) return Collections.emptyList();
    String leftConst = ObjectUtils.tryCast(getConstantValue(memState, qualifier), String.class);
    String rightConst = ObjectUtils.tryCast(getConstantValue(memState, arg), String.class);
    if (leftConst != null && rightConst != null) {
      return singleResult(memState, factory.getBoolean(ends ? leftConst.endsWith(rightConst) : leftConst.startsWith(rightConst)));
    }
    DfaValue leftLength = memState.getStringLength(qualifier);
    DfaValue rightLength = memState.getStringLength(arg);
    DfaRelationValue trueRelation = factory.getRelationFactory().createRelation(leftLength, rightLength, GE, false);
    DfaRelationValue falseRelation = factory.getRelationFactory().createRelation(leftLength, rightLength, LT, false);
    return applyCondition(memState, trueRelation, DfaUnknownValue.getInstance(), falseRelation, factory.getBoolean(false));
  }

  private static List<DfaMemoryState> stringEquals(DfaValue qualifier,
                                                   DfaValue[] args,
                                                   DfaMemoryState memState,
                                                   DfaValueFactory factory,
                                                   boolean ignoreCase) {
    DfaValue arg = ArrayUtil.getFirstElement(args);
    if (arg == null) return Collections.emptyList();
    String leftConst = ObjectUtils.tryCast(getConstantValue(memState, qualifier), String.class);
    String rightConst = ObjectUtils.tryCast(getConstantValue(memState, arg), String.class);
    if (leftConst != null && rightConst != null) {
      return singleResult(memState, factory.getBoolean(ignoreCase ? leftConst.equalsIgnoreCase(rightConst) : leftConst.equals(rightConst)));
    }
    DfaValue leftLength = memState.getStringLength(qualifier);
    DfaValue rightLength = memState.getStringLength(arg);
    DfaRelationValue trueRelation = factory.getRelationFactory().createRelation(leftLength, rightLength, EQ, false);
    DfaRelationValue falseRelation = factory.getRelationFactory().createRelation(leftLength, rightLength, NE, false);
    return applyCondition(memState, trueRelation, DfaUnknownValue.getInstance(), falseRelation, factory.getBoolean(false));
  }

  private static List<DfaMemoryState> stringIndexOf(DfaValue qualifier,
                                                    DfaMemoryState memState,
                                                    DfaValueFactory factory) {
    DfaValue length = memState.getStringLength(qualifier);
    LongRangeSet range = memState.getRange(length);
    long maxLen = range == null || range.isEmpty() ? Integer.MAX_VALUE : range.max();
    return singleResult(memState, factory.getRangeFactory().create(LongRangeSet.range(-1, maxLen - 1)));
  }

  private static List<DfaMemoryState> stringIsEmpty(DfaValue qualifier, DfaMemoryState memState, DfaValueFactory factory) {
    DfaValue length = memState.getStringLength(qualifier);
    if (length == DfaUnknownValue.getInstance()) {
      return singleResult(memState, DfaUnknownValue.getInstance());
    }
    DfaConstValue zero = factory.getConstFactory().createFromValue(0, PsiType.INT, null);
    DfaRelationValue trueRelation = factory.getRelationFactory().createRelation(length, zero, EQEQ, false);
    DfaRelationValue falseRelation = factory.getRelationFactory().createRelation(length, zero, NE, false);
    return applyCondition(memState, trueRelation, factory.getBoolean(true), falseRelation, factory.getBoolean(false));
  }

  private static List<DfaMemoryState> mathMinMax(DfaValue[] args, DfaMemoryState memState, DfaValueFactory factory, boolean max) {
    if(args == null || args.length != 2) return Collections.emptyList();
    LongRangeSet first = memState.getRange(args[0]);
    LongRangeSet second = memState.getRange(args[1]);
    if (first == null || second == null || first.isEmpty() || second.isEmpty()) return Collections.emptyList();
    LongRangeSet domain = max ? LongRangeSet.range(Math.max(first.min(), second.min()), Long.MAX_VALUE)
                          : LongRangeSet.range(Long.MIN_VALUE, Math.min(first.max(), second.max()));
    LongRangeSet result = first.union(second).intersect(domain);
    return singleResult(memState, factory.getRangeFactory().create(result));
  }

  private static List<DfaMemoryState> mathAbs(DfaValue[] args, DfaMemoryState memState, DfaValueFactory factory, boolean isLong) {
    DfaValue arg = ArrayUtil.getFirstElement(args);
    if(arg == null) return Collections.emptyList();
    LongRangeSet range = memState.getRange(arg);
    if (range == null) return Collections.emptyList();
    return singleResult(memState, factory.getRangeFactory().create(range.abs(isLong)));
  }

  private static List<DfaMemoryState> singleResult(DfaMemoryState state, DfaValue value) {
    state.push(value);
    return Collections.singletonList(state);
  }

  @NotNull
  private static List<DfaMemoryState> applyCondition(DfaMemoryState memState,
                                                     DfaRelationValue trueRelation,
                                                     DfaValue trueResult,
                                                     DfaRelationValue falseRelation,
                                                     DfaValue falseResult) {
    DfaMemoryState falseState = memState.createCopy();
    List<DfaMemoryState> result = new ArrayList<>(2);
    if (memState.applyCondition(trueRelation)) {
      memState.push(trueResult);
      result.add(memState);
    }
    if (falseState.applyCondition(falseRelation)) {
      falseState.push(falseResult);
      result.add(falseState);
    }
    return result;
  }

  private static Object getConstantValue(DfaMemoryState memoryState, DfaValue value) {
    if (value instanceof DfaVariableValue) {
      value = memoryState.getConstantValue((DfaVariableValue)value);
    }
    if (value instanceof DfaConstValue) {
      return ((DfaConstValue)value).getValue();
    }
    return null;
  }
}
