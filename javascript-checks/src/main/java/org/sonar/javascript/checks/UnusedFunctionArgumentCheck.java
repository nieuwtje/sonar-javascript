/*
 * SonarQube JavaScript Plugin
 * Copyright (C) 2011 SonarSource and Eriks Nukis
 * dev@sonar.codehaus.org
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02
 */
package org.sonar.javascript.checks;

import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.sonar.sslr.api.AstNode;
import com.sonar.sslr.squid.checks.SquidCheck;
import org.apache.commons.lang.ArrayUtils;
import org.apache.commons.lang.StringUtils;
import org.sonar.check.BelongsToProfile;
import org.sonar.check.Priority;
import org.sonar.check.Rule;
import org.sonar.javascript.api.EcmaScriptTokenType;
import org.sonar.javascript.parser.EcmaScriptGrammar;
import org.sonar.sslr.parser.LexerlessGrammar;

import java.util.List;
import java.util.Map;

@Rule(
  key = "UnusedFunctionArgument",
  priority = Priority.MAJOR)
@BelongsToProfile(title = CheckList.SONAR_WAY_PROFILE, priority = Priority.MAJOR)
public class UnusedFunctionArgumentCheck extends SquidCheck<LexerlessGrammar> {

  private static class Scope {
    private final Scope outerScope;
    private final AstNode functionDec;
    private final Map<String, Integer> arguments;
    private boolean useArgumentsArray = false;

    public Scope(Scope outerScope, AstNode functionDec) {
      this.outerScope = outerScope;
      this.functionDec = functionDec;
      this.arguments = Maps.newLinkedHashMap();
    }

    private void declare(AstNode astNode) {
      Preconditions.checkState(astNode.is(EcmaScriptTokenType.IDENTIFIER));
      String identifier = astNode.getTokenValue();
      arguments.put(identifier, 0);
    }

    private void use(AstNode astNode) {
      Preconditions.checkState(astNode.is(EcmaScriptTokenType.IDENTIFIER));
      String identifier = astNode.getTokenValue();
      Scope scope = this;
      while (scope != null) {
        Integer usage = scope.arguments.get(identifier);
        if (usage != null) {
          usage++;
          scope.arguments.put(identifier, usage);
          return;
        }
        scope = scope.outerScope;
      }
    }
  }

  private Scope currentScope;

  @Override
  public void init() {
    subscribeTo(
      EcmaScriptGrammar.FUNCTION_EXPRESSION,
      EcmaScriptGrammar.FUNCTION_DECLARATION,
      EcmaScriptGrammar.FORMAL_PARAMETER_LIST,
      EcmaScriptGrammar.PRIMARY_EXPRESSION);
  }

  @Override
  public void visitFile(AstNode astNode) {
    currentScope = null;
  }

  @Override
  public void visitNode(AstNode astNode) {
    if (astNode.is(EcmaScriptGrammar.FUNCTION_EXPRESSION, EcmaScriptGrammar.FUNCTION_DECLARATION)) {
      // enter new scope
      currentScope = new Scope(currentScope, astNode);
    } else if (astNode.is(EcmaScriptGrammar.FORMAL_PARAMETER_LIST)) {
      for (AstNode identifierNode : astNode.getChildren(EcmaScriptTokenType.IDENTIFIER)) {
        currentScope.declare(identifierNode);
      }
    } else if (currentScope != null && astNode.is(EcmaScriptGrammar.PRIMARY_EXPRESSION)) {
      AstNode identifierNode = astNode.getFirstChild(EcmaScriptTokenType.IDENTIFIER);
      if (identifierNode != null) {
        if ("arguments".equals(identifierNode.getTokenValue())) {
          currentScope.useArgumentsArray = true;
        }
        currentScope.use(identifierNode);
      }
    }
  }

  @Override
  public void leaveNode(AstNode astNode) {
    if (astNode.is(EcmaScriptGrammar.FUNCTION_EXPRESSION, EcmaScriptGrammar.FUNCTION_DECLARATION)) {
      // leave scope
      if (!currentScope.useArgumentsArray) {
        reportUnusedArguments(astNode);
      }
      currentScope = currentScope.outerScope;
    }
  }

  @Override
  public void leaveFile(AstNode astNode) {
    currentScope = null;
  }

  public void reportUnusedArguments(AstNode functionNode) {
    if (functionNode.is(EcmaScriptGrammar.FUNCTION_DECLARATION)) {
      reportAllUnusedArgs();
    } else {
      reportDanglingUnusedArgs();
    }
  }

  public void reportAllUnusedArgs() {
    int nbUnusedArgs = 0;
    StringBuilder builder = new StringBuilder();

    for (Map.Entry<String, Integer> entry : currentScope.arguments.entrySet()) {
      if (entry.getValue() == 0) {
        builder.append(entry.getKey() + " ");
        nbUnusedArgs++;
      }
    }
    createIssue(builder, false, nbUnusedArgs);
  }

  public void reportDanglingUnusedArgs() {
    int nbUnusedArgs = 0;
    boolean hasMetUsedArg = false;
    StringBuilder builder = new StringBuilder();
    List<Map.Entry<String, Integer>> entries = Lists.newArrayList(currentScope.arguments.entrySet());

    for (Map.Entry<String, Integer> entry : Lists.reverse(entries)) {
      int usages = entry.getValue();

      if (usages == 0 && !hasMetUsedArg) {
        builder.append(entry.getKey() + " ");
        nbUnusedArgs++;
      } else if (usages > 0 && !hasMetUsedArg) {
        hasMetUsedArg = true;
      }
    }
    createIssue(builder, true, nbUnusedArgs);
  }

  public void createIssue(StringBuilder builder, boolean reverse, int nbArgs) {
    String argsList;
    if (nbArgs > 1) {

      if (reverse) {
        String[] args = builder.deleteCharAt(builder.length() - 1).toString().split(" ");
        ArrayUtils.reverse(args);
        argsList = StringUtils.join(args, ", ");
      } else {
        argsList = StringUtils.join(builder.toString().split(" "), ", ");
      }

      getContext().createLineViolation(this, "Remove the unused function parameters \"" + argsList + "\".", currentScope.functionDec);
    } else if (nbArgs == 1) {
      getContext().createLineViolation(this, "Remove the unused function parameter \"" + builder.toString().trim() + "\".", currentScope.functionDec);
    }
  }
}

