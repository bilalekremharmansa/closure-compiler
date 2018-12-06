/*
 * Copyright 2009 The Closure Compiler Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.javascript.jscomp;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import com.google.common.base.Joiner;
import com.google.javascript.rhino.Node;
import com.google.javascript.rhino.jstype.FunctionType;
import com.google.javascript.rhino.jstype.JSType;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Tests for {@link DevirtualizePrototypeMethods}
 *
 */
@RunWith(JUnit4.class)
public final class DevirtualizePrototypeMethodsTest extends CompilerTestCase {
  private static final String EXTERNAL_SYMBOLS =
      DEFAULT_EXTERNS + "var extern;extern.externalMethod";

  public DevirtualizePrototypeMethodsTest() {
    super(EXTERNAL_SYMBOLS);
  }

  @Override
  protected int getNumRepetitions() {
    // run pass once.
    return 1;
  }

  @Override
  @Before
  public void setUp() throws Exception {
    super.setUp();
    disableTypeCheck();
  }

  /**
   * Combine source strings using ';' as the separator.
   */
  private static String semicolonJoin(String ... parts) {
    return Joiner.on(";").join(parts);
  }

  @Test
  public void testRewritePrototypeMethodsWithCorrectTypes() {
    String input =
        lines(
            "/** @constructor */",
            "function A() { this.x = 3; }",
            "/** @return {number} */",
            "A.prototype.foo = function() { return this.x; };",
            "/** @param {number} p",
            "    @return {number} */",
            "A.prototype.bar = function(p) { return this.x; };",
            "A.prototype.baz = function() {};",
            "var o = new A();",
            "o.foo();",
            "o.bar(2);",
            "o.baz()");
    String expected =
        lines(
            "/** @constructor */",
            "function A(){ this.x = 3; }",
            "var JSCompiler_StaticMethods_foo = ",
            "function(JSCompiler_StaticMethods_foo$self) {",
            "  return JSCompiler_StaticMethods_foo$self.x",
            "};",
            "var JSCompiler_StaticMethods_bar = ",
            "function(JSCompiler_StaticMethods_bar$self, p) {",
            "  return JSCompiler_StaticMethods_bar$self.x",
            "};",
            "var JSCompiler_StaticMethods_baz = ",
            "function(JSCompiler_StaticMethods_baz$self) {",
            "};",
            "var o = new A();",
            "JSCompiler_StaticMethods_foo(o);",
            "JSCompiler_StaticMethods_bar(o, 2);",
            "JSCompiler_StaticMethods_baz(o)");

    enableTypeCheck();
    test(input, expected);
    checkTypeOfRewrittenMethods();
  }

  private void checkTypeOfRewrittenMethods() {
    JSType thisType = getTypeAtPosition(0).toMaybeFunctionType().getInstanceType();
    FunctionType fooType = getTypeAtPosition(1, 0, 0).toMaybeFunctionType();
    FunctionType barType = getTypeAtPosition(2, 0, 0).toMaybeFunctionType();
    FunctionType bazType = getTypeAtPosition(3, 0, 0).toMaybeFunctionType();
    JSType fooResultType = getTypeAtPosition(5, 0);
    JSType barResultType = getTypeAtPosition(6, 0);
    JSType bazResultType = getTypeAtPosition(7, 0);

    JSType number = fooResultType;
    JSType receiver = fooType.getTypeOfThis();
    assertWithMessage("Expected number: " + number).that(number.isNumberValueType()).isTrue();
    // NOTE: The type checker has the receiver as unknown
    assertWithMessage("Expected null or unknown: " + receiver)
        .that(receiver == null || receiver.isUnknownType())
        .isTrue();
    assertThat(barResultType).isEqualTo(number);

    // Check that foo's type is {function(A): number}
    assertThat(fooType.getParameterTypes()).containsExactly(thisType);
    assertThat(fooType.getReturnType()).isEqualTo(number);
    assertThat(fooType.getTypeOfThis()).isEqualTo(receiver);

    // Check that bar's type is {function(A, number): number}
    assertThat(barType.getParameterTypes()).containsExactly(thisType, number).inOrder();
    assertThat(barType.getReturnType()).isEqualTo(number);
    assertThat(barType.getTypeOfThis()).isEqualTo(receiver);

    // Check that baz's type is {function(A): undefined}
    assertThat(bazType.getParameterTypes()).containsExactly(thisType);
    assertThat(bazType.getTypeOfThis()).isEqualTo(receiver);

    // TODO(sdh): NTI currently fails to infer the result of the baz() call (b/37351897)
    // so we handle it more carefully.  When methods are deferred, this should be changed
    // to check that it's exactly unknown.
    assertWithMessage("Expected undefined or unknown: " + bazResultType)
        .that(bazResultType.isVoidType() || bazResultType.isUnknownType())
        .isTrue();
    assertWithMessage("Expected undefined: " + bazType.getReturnType())
        .that(bazType.getReturnType().isVoidType())
        .isTrue();
  }

  private JSType getTypeAtPosition(int... indices) {
    Node node = getLastCompiler().getJsRoot().getFirstChild();
    for (int index : indices) {
      node = node.getChildAtIndex(index);
    }
    return node.getJSType();
  }

  @Test
  public void testRewriteChained() {
    String source =
        lines(
            "A.prototype.foo = function(){return this.b};",
            "B.prototype.bar = function(){};",
            "o.foo().bar()");

    String expected =
        lines(
            "var JSCompiler_StaticMethods_foo = ",
            "function(JSCompiler_StaticMethods_foo$self) {",
            "  return JSCompiler_StaticMethods_foo$self.b",
            "};",
            "var JSCompiler_StaticMethods_bar = ",
            "function(JSCompiler_StaticMethods_bar$self) {",
            "};",
            "JSCompiler_StaticMethods_bar(JSCompiler_StaticMethods_foo(o))");
    test(source, expected);
  }

  /**
   * Inputs for declaration used as an r-value tests.
   */
  private static class NoRewriteDeclarationUsedAsRValue {
    static final String DECL = "a.prototype.foo = function() {}";
    static final String CALL = "o.foo()";

    private NoRewriteDeclarationUsedAsRValue() {}
  }

  @Test
  public void testRewriteDeclIsExpressionStatement() {
    test(semicolonJoin(NoRewriteDeclarationUsedAsRValue.DECL,
                       NoRewriteDeclarationUsedAsRValue.CALL),
         "var JSCompiler_StaticMethods_foo =" +
         "function(JSCompiler_StaticMethods_foo$self) {};" +
         "JSCompiler_StaticMethods_foo(o)");
  }

  @Test
  public void testNoRewriteDeclUsedAsAssignmentRhs() {
    testSame(semicolonJoin("var c = " + NoRewriteDeclarationUsedAsRValue.DECL,
                           NoRewriteDeclarationUsedAsRValue.CALL));
  }

  @Test
  public void testNoRewriteDeclUsedAsCallArgument() {
    testSame(semicolonJoin("f(" + NoRewriteDeclarationUsedAsRValue.DECL + ")",
                           NoRewriteDeclarationUsedAsRValue.CALL));
  }

  @Test
  public void testRewrite_ifDefined_unconditionally() {
    test(
        lines(
            "function a(){}",
            "a.prototype.foo = function() {return this.x};",
            "var o = new a;",
            "o.foo()"),
        lines(
            "function a(){}",
            "var JSCompiler_StaticMethods_foo = ",
            "function(JSCompiler_StaticMethods_foo$self) {",
            "  return JSCompiler_StaticMethods_foo$self.x",
            "};",
            "var o = new a;",
            "JSCompiler_StaticMethods_foo(o);"));
  }

  private void testNoRewriteIfDefinitionSiteBetween(String prefix, String suffix) {
    testSame(
        lines(
            "function a(){}",
            prefix + "a.prototype.foo = function() {return this.x}" + suffix + ";",
            "var o = new a;",
            "o.foo()"));
  }

  @Test
  public void testNoRewrite_ifDefinedIn_ifScope() {
    testNoRewriteIfDefinitionSiteBetween("if (true) ", "");
  }

  @Test
  public void testNoRewrite_ifDefinedIn_loopScope() {
    testNoRewriteIfDefinitionSiteBetween("while (true) ", "");
  }

  @Test
  public void testNoRewrite_ifDefinedIn_switchScope() {
    testNoRewriteIfDefinitionSiteBetween("switch (true) { case true: ", "; }");
  }

  @Test
  public void testNoRewrite_ifDefinedIn_functionScope() {
    testNoRewriteIfDefinitionSiteBetween("function f() { ", "; }");
  }

  @Test
  public void testNoRewrite_ifDefinedIn_arrowFunctionScope() {
    testNoRewriteIfDefinitionSiteBetween("() => ", "");
  }

  @Test
  public void testNoRewrite_ifDefinedIn_blockScope() {
    // Some declarations are block scoped in ES6 and so might have different values. This could make
    // multiple definitions with identical node structure behave differently.
    testNoRewriteIfDefinitionSiteBetween("{ ", "; }");
  }

  @Test
  public void testNoRewrite_ifDefinedBy_andExpression_withLiteral() {
    // TODO(nickreid): This may be unnecessarily restrictive. We could probably rely on the
    // definitions being identical or not to filter this case.
    testNoRewriteIfDefinitionSiteBetween("", " && function() {}");
  }

  @Test
  public void testNoRewrite_ifDefinedBy_andExpression_withReference() {
    testNoRewriteIfDefinitionSiteBetween("", " && bar");
  }

  @Test
  public void testNoRewrite_ifDefinedBy_orExpression_withLiteral() {
    // TODO(nickreid): This may be unnecessarily restrictive. We could probably rely on the
    // definitions being identical or not to filter this case.
    testNoRewriteIfDefinitionSiteBetween("", " || function() {}");
  }

  @Test
  public void testNoRewrite_ifDefinedBy_orExpression_withReference() {
    testNoRewriteIfDefinitionSiteBetween("", " || bar");
  }

  @Test
  public void testNoRewrite_ifDefinedBy_ternaryExpression_withLiteral() {
    // TODO(nickreid): This may be unnecessarily restrictive. We could probably rely on the
    // definitions being identical or not to filter this case.

    // Make the functions look different, just to be safe.
    testNoRewriteIfDefinitionSiteBetween("", " ? function() { this.a; } : function() { this.b; }");
  }

  @Test
  public void testNoRewrite_ifDefinedBy_ternaryExpression_withReference() {
    testNoRewriteIfDefinitionSiteBetween("", " ? function() { } : bar");
  }

  @Test
  public void testNoRewrite_namespaceFunctions() {
    String source = "function a(){}; a.foo = function() {return this.x}; a.foo()";
    testSame(source);
  }

  @Test
  public void testRewrite_ifMultipleIdenticalDefinitions() {
    test(
        lines(
            "function A(){};",
            "A.prototype.getFoo = function() { return 1; }; ",
            "",
            "function B(){};",
            "B.prototype.getFoo = function() { return 1; }; ",
            "",
            "(Math.random() ? new A() : new B()).getFoo();"),
        lines(
            "function A(){}; ",
            "var JSCompiler_StaticMethods_getFoo=",
            "  function(JSCompiler_StaticMethods_getFoo$self){return 1};",
            "",
            "function B(){};",
            // Dead definition.
            "B.prototype.getFoo=function(){return 1};",
            "",
            "JSCompiler_StaticMethods_getFoo(Math.random() ? new A() : new B());"));
  }

  @Test
  public void testRewrite_ifMultipleIdenticalDefinitions_ensuresDefinitionBeforeInvocations() {
    test(
        lines(
            "function A(){};",
            "A.prototype.getFoo = function() { return 1; }; ",
            "",
            "(new A()).getFoo();",
            "",
            "function B(){};",
            "B.prototype.getFoo = function() { return 1; }; ",
            "",
            "(new B()).getFoo();"),
        lines(
            "function A(){}; ",
            "var JSCompiler_StaticMethods_getFoo=",
            "  function(JSCompiler_StaticMethods_getFoo$self){return 1};",
            "",
            "JSCompiler_StaticMethods_getFoo(new A());",
            "",
            "function B(){};",
            // Dead definition.
            "B.prototype.getFoo=function(){return 1};",
            "",
            "JSCompiler_StaticMethods_getFoo(new B());"));
  }

  @Test
  public void testRewrite_ifMultipleIdenticalDefinitions_withThis() {
    test(
        lines(
            "function A(){}; A.prototype.getFoo = ",
            "function() { return this._foo + 1; }; ",
            "function B(){}; B.prototype.getFoo = ",
            "function() { return this._foo + 1; }; ",
            "var x = Math.random() ? new A() : new B();",
            "alert(x.getFoo());"),
        lines(
            "function A(){}; ",
            "var JSCompiler_StaticMethods_getFoo=",
            "function(JSCompiler_StaticMethods_getFoo$self){",
            "  return JSCompiler_StaticMethods_getFoo$self._foo + 1",
            "};",
            "function B(){};",
            "B.prototype.getFoo=function(){return this._foo + 1};",
            "var x = Math.random() ? new A() : new B();",
            "alert(JSCompiler_StaticMethods_getFoo(x));"));
  }

  @Test
  public void testNoRewrite_ifMultipleDistinctDefinitions() {
    testSame(
        lines(
            "function A(){}; A.prototype.getFoo = function() { return 1; }; ",
            "function B(){}; B.prototype.getFoo = function() { return 2; }; ",
            "var x = Math.random() ? new A() : new B();",
            "alert(x.getFoo());"));
  }

  /**
   * Inputs for object literal tests.
   */
  private static class NoRewritePrototypeObjectLiteralsTestInput {
    static final String REGULAR = "b.prototype.foo = function() { return 1; }";
    static final String OBJ_LIT = "a.prototype = {foo : function() { return 2; }}";
    static final String CALL = "o.foo()";

    private NoRewritePrototypeObjectLiteralsTestInput() {}
  }

  @Test
  public void testRewritePrototypeNoObjectLiterals() {
    test(
        semicolonJoin(
            NoRewritePrototypeObjectLiteralsTestInput.REGULAR,
            NoRewritePrototypeObjectLiteralsTestInput.CALL),
        lines(
            "var JSCompiler_StaticMethods_foo = ",
            "function(JSCompiler_StaticMethods_foo$self) { return 1; };",
            "JSCompiler_StaticMethods_foo(o)"));
  }

  @Test
  public void testRewrite_definedUsingProtoObjectLit() {
    test(
        semicolonJoin(
            NoRewritePrototypeObjectLiteralsTestInput.OBJ_LIT,
            NoRewritePrototypeObjectLiteralsTestInput.CALL),
        lines(
            "a.prototype={};",
            "var JSCompiler_StaticMethods_foo=",
            "function(JSCompiler_StaticMethods_foo$self){ return 2; };",
            "JSCompiler_StaticMethods_foo(o)"));
  }

  @Test
  public void testNoRewrite_multipleDefinitions_definedUsingProtoObjectLit_definedUsingGetProp() {
    testSame(semicolonJoin(NoRewritePrototypeObjectLiteralsTestInput.OBJ_LIT,
                           NoRewritePrototypeObjectLiteralsTestInput.REGULAR,
                           NoRewritePrototypeObjectLiteralsTestInput.CALL));
  }

  @Test
  public void testNoRewrite_noDefinition() {
    testSame("a.externalMethod()");
  }

  @Test
  public void testNoRewrite_externMethod() {
    testSame(
        externs("A.prototype.externalMethod = function(){};"), //
        srcs("o.externalMethod()"));
  }

  @Test
  public void testNoRewrite_exportedMethod_viaCodingConvention() {
    // no rename, leading _ indicates exported symbol
    testSame("a.prototype._foo = function() {};");
  }

  @Test
  public void testRewriteNoVarArgs() {
    String source =
        lines(
            "function a(){}",
            "a.prototype.foo = function(args) {return args};",
            "var o = new a;",
            "o.foo()");

    String expected =
        lines(
            "function a(){}",
            "var JSCompiler_StaticMethods_foo = ",
            "  function(JSCompiler_StaticMethods_foo$self, args) {return args};",
            "var o = new a;",
            "JSCompiler_StaticMethods_foo(o)");

    test(source, expected);
  }

  @Test
  public void testNoRewriteVarArgs() {
    String source =
        lines(
            "function a(){}",
            "a.prototype.foo = function(var_args) {return arguments};",
            "var o = new a;",
            "o.foo()");
    testSame(source);
  }

  /**
   * Inputs for invalidating reference tests.
   */
  private static class NoRewriteNonCallReferenceTestInput {
    static final String BASE =
        "function a(){}\na.prototype.foo = function() {return this.x};\nvar o = new a;";

    private NoRewriteNonCallReferenceTestInput() {}
  }

  @Test
  public void testRewrite_callReference() {
    String expected =
        lines(
            "function a(){}",
            "var JSCompiler_StaticMethods_foo = ",
            "function(JSCompiler_StaticMethods_foo$self) {",
            "  return JSCompiler_StaticMethods_foo$self.x",
            "};",
            "var o = new a;",
            "JSCompiler_StaticMethods_foo(o);");

    test(NoRewriteNonCallReferenceTestInput.BASE + "o.foo()", expected);
  }

  @Test
  public void testNoRewrite_noReferences() {
    testSame(NoRewriteNonCallReferenceTestInput.BASE);
  }

  @Test
  public void testNoRewrite_nonCallReference_viaGetprop() {
    testSame(NoRewriteNonCallReferenceTestInput.BASE + "o.foo && o.foo()");
  }

  @Test
  public void testNoRewrite_nonCallReference_viaGetprop_usingFnCall() {
    // TODO(nickreid): Add rewriting support for this.
    testSame(NoRewriteNonCallReferenceTestInput.BASE + "o.foo.call(null) && o.foo()");
  }

  @Test
  public void testNoRewrite_nonCallReference_viaGetprop_usingFnApply() {
    // TODO(nickreid): Add rewriting support for this.
    testSame(NoRewriteNonCallReferenceTestInput.BASE + "o.foo.apply(null) && o.foo()");
  }

  @Test
  public void testNoRewrite_nonCallReference_viaGetprop_asArgument() {
    testSame(NoRewriteNonCallReferenceTestInput.BASE + "bar(o.foo, null) && o.foo()");
  }

  @Test
  public void testNoRewrite_nonCallReference_viaNew() {
    // TODO(nickreid): Add rewriting support for this.
    testSame(NoRewriteNonCallReferenceTestInput.BASE + "new o.foo() && o.foo()");
  }

  /**
   * Inputs for nested definition tests.
   */
  private static class NoRewriteNestedFunctionTestInput {
    static final String PREFIX = "a.prototype.foo = function() {";
    static final String SUFFIX = "o.foo()";
    static final String INNER = "a.prototype.bar = function() {}; o.bar()";
    static final String EXPECTED_PREFIX =
        "var JSCompiler_StaticMethods_foo=" +
        "function(JSCompiler_StaticMethods_foo$self){";
    static final String EXPECTED_SUFFIX =
        "JSCompiler_StaticMethods_foo(o)";

    private NoRewriteNestedFunctionTestInput() {}
  }

  @Test
  public void testRewriteNoNestedFunction() {
    test(semicolonJoin(
             NoRewriteNestedFunctionTestInput.PREFIX + "}",
             NoRewriteNestedFunctionTestInput.SUFFIX,
             NoRewriteNestedFunctionTestInput.INNER),
         semicolonJoin(
             NoRewriteNestedFunctionTestInput.EXPECTED_PREFIX + "}",
             NoRewriteNestedFunctionTestInput.EXPECTED_SUFFIX,
             "var JSCompiler_StaticMethods_bar=" +
             "function(JSCompiler_StaticMethods_bar$self){}",
             "JSCompiler_StaticMethods_bar(o)"));
  }

  @Test
  public void testNoRewriteNestedFunction() {
    test(NoRewriteNestedFunctionTestInput.PREFIX +
         NoRewriteNestedFunctionTestInput.INNER + "};" +
         NoRewriteNestedFunctionTestInput.SUFFIX,
         NoRewriteNestedFunctionTestInput.EXPECTED_PREFIX +
         NoRewriteNestedFunctionTestInput.INNER + "};" +
         NoRewriteNestedFunctionTestInput.EXPECTED_SUFFIX);
  }

  @Test
  public void testRewrite_definedUsingGetProp_with_callUsingGetProp() {
    String source =
        lines(
            "function a(){}",
            "a.prototype.foo = function(args) {return args};",
            "var o = new a;",
            "o.foo()");
    String expected =
        lines(
            "function a(){}",
            "var JSCompiler_StaticMethods_foo = ",
            "  function(JSCompiler_StaticMethods_foo$self, args) {return args};",
            "var o = new a;",
            "JSCompiler_StaticMethods_foo(o)");
    test(source, expected);
  }

  @Test
  public void testRewrite_definedUsingGetElem_with_callUsingGetProp() {
    String source =
        lines(
            "function a(){}",
            "a.prototype['foo'] = function(args) {return args};",
            "var o = new a;",
            "o.foo()");
    testSame(source);
  }

  @Test
  public void testNoRewrite_definedUsingGetProp_with_noCall_bracketAccess() {
    String source =
        lines(
            "function a(){}",
            "a.prototype.foo = function(args) {return args};",
            "var o = new a;",
            "o['foo']");
    testSame(source);
  }

  @Test
  public void testNoRewrite_definedUsingGetElem_with_noCall_bracketAccess() {
    String source =
        lines(
            "function a(){}",
            "a.prototype['foo'] = function(args) {return args};",
            "var o = new a;",
            "o['foo']");
    testSame(source);
  }

  @Test
  public void testRewrite_definedOnScopeCreatingExpression_reportsScopeAsDeleted() {
    test(
        lines(
            "(function() {}).prototype.foo = function() {extern();};",
            // A call is needed to trigger rewriting.
            "a.foo();"),
        lines(
            "var JSCompiler_StaticMethods_foo = function(JSCompiler_StaticMethods_foo$self) {",
            "  extern();",
            "};",
            "JSCompiler_StaticMethods_foo(a);"));
  }

  @Test
  public void testRewrite_definedOnExpressionWithSideEffects() {
    // TODO(nickreid): Expect this not to be a rewrite (or confirm it's safe).
    test(
        lines(
            "extern().prototype.foo = function() { };",
            // A call is needed to trigger rewriting.
            "a.foo()"),
        // We think this is risky because if `extern()` had side effects they'd be eliminated.
        lines(
            "var JSCompiler_StaticMethods_foo=function(JSCompiler_StaticMethods_foo$self){};",
            "JSCompiler_StaticMethods_foo(a)"));
  }

  @Test
  public void testRewrite_definedUsingStringKey_inPrototypeLiteral_with_callUsingGetProp() {
    test(
        lines(
            "function a(){}",
            "a.prototype = {foo: function(args) {return args}};",
            "var o = new a;",
            "o.foo()"),
        lines(
            "function a(){}",
            "a.prototype={};",
            "var JSCompiler_StaticMethods_foo=",
            "function(JSCompiler_StaticMethods_foo$self,args){return args};",
            "var o=new a;",
            "JSCompiler_StaticMethods_foo(o)"));
  }

  @Test
  public void testNoRewriteGet1() {
    // Getters and setter require special handling.
    testSame("function a(){}; a.prototype = {get foo(){return f}}; var o = new a; o.foo()");
  }

  @Test
  public void testNoRewriteGet2() {
    // Getters and setter require special handling.
    testSame("function a(){}; a.prototype = {get foo(){return 1}}; var o = new a; o.foo");
  }

  @Test
  public void testNoRewriteSet1() {
    // Getters and setter require special handling.
    String source = "function a(){}; a.prototype = {set foo(a){}}; var o = new a; o.foo()";
    testSame(source);
  }

  @Test
  public void testNoRewriteSet2() {
    // Getters and setter require special handling.
    String source = "function a(){}; a.prototype = {set foo(a){}}; var o = new a; o.foo = 1";
    testSame(source);
  }

  @Test
  public void testNoRewrite_notImplementedMethod() {
    testSame("function a(){}; var o = new a; o.foo()");
  }

  @Test
  public void testWrapper() {
    testSame("(function() {})()");
  }

  private static class ModuleTestInput {
    static final String DEFINITION = "a.prototype.foo = function() {}";
    static final String USE = "x.foo()";

    static final String REWRITTEN_DEFINITION =
        "var JSCompiler_StaticMethods_foo=" + "function(JSCompiler_StaticMethods_foo$self){}";
    static final String REWRITTEN_USE = "JSCompiler_StaticMethods_foo(x)";

    private ModuleTestInput() {}
  }

  @Test
  public void testRewriteSameModule1() {
    JSModule[] modules =
        createModuleStar(
            // m1
            semicolonJoin(ModuleTestInput.DEFINITION, ModuleTestInput.USE),
            // m2
            "");

    test(
        modules,
        new String[] {
          // m1
          semicolonJoin(ModuleTestInput.REWRITTEN_DEFINITION, ModuleTestInput.REWRITTEN_USE),
          // m2
          "",
        });
  }

  @Test
  public void testRewriteSameModule2() {
    JSModule[] modules =
        createModuleStar(
            // m1
            "",
            // m2
            semicolonJoin(ModuleTestInput.DEFINITION, ModuleTestInput.USE));

    test(
        modules,
        new String[] {
          // m1
          "",
          // m2
          semicolonJoin(ModuleTestInput.REWRITTEN_DEFINITION, ModuleTestInput.REWRITTEN_USE)
        });
  }

  @Test
  public void testRewriteSameModule3() {
    JSModule[] modules =
        createModuleStar(
            // m1
            semicolonJoin(ModuleTestInput.USE, ModuleTestInput.DEFINITION),
            // m2
            "");

    test(
        modules,
        new String[] {
          // m1
          semicolonJoin(ModuleTestInput.REWRITTEN_USE, ModuleTestInput.REWRITTEN_DEFINITION),
          // m2
          ""
        });
  }

  @Test
  public void testRewriteDefinitionBeforeUse() {
    JSModule[] modules =
        createModuleStar(
            // m1
            ModuleTestInput.DEFINITION,
            // m2
            ModuleTestInput.USE);

    test(
        modules,
        new String[] {
          // m1
          ModuleTestInput.REWRITTEN_DEFINITION,
          // m2
          ModuleTestInput.REWRITTEN_USE
        });
  }

  @Test
  public void testNoRewriteUseBeforeDefinition() {
    JSModule[] modules =
        createModuleStar(
            // m1
            ModuleTestInput.USE,
            // m2
            ModuleTestInput.DEFINITION);

    testSame(modules);
  }

  @Override
  protected CompilerPass getProcessor(Compiler compiler) {
    OptimizeCalls pass = new OptimizeCalls(compiler);
    pass.addPass(new DevirtualizePrototypeMethods(compiler));
    return pass;
  }
}
