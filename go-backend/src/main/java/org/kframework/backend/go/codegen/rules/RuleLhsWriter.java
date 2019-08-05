// Copyright (c) 2015-2019 K Team. All Rights Reserved.
package org.kframework.backend.go.codegen.rules;

import org.kframework.attributes.Att;
import org.kframework.backend.go.codegen.GoBuiltin;
import org.kframework.backend.go.codegen.inline.RuleLhsMatchWriter;
import org.kframework.backend.go.model.DefinitionData;
import org.kframework.backend.go.model.FunctionParams;
import org.kframework.backend.go.model.VarContainer;
import org.kframework.backend.go.strings.GoNameProvider;
import org.kframework.backend.go.strings.GoStringBuilder;
import org.kframework.kil.Attribute;
import org.kframework.kore.InjectedKLabel;
import org.kframework.kore.K;
import org.kframework.kore.KApply;
import org.kframework.kore.KAs;
import org.kframework.kore.KLabel;
import org.kframework.kore.KORE;
import org.kframework.kore.KRewrite;
import org.kframework.kore.KSequence;
import org.kframework.kore.KToken;
import org.kframework.kore.KVariable;
import org.kframework.kore.Sort;
import org.kframework.kore.VisitK;
import org.kframework.parser.outer.Outer;
import org.kframework.unparser.ToKast;
import org.kframework.utils.errorsystem.KEMException;

import java.util.List;
import java.util.Set;

public class RuleLhsWriter extends VisitK {
    private final GoStringBuilder sb;
    private final DefinitionData data;
    private final GoNameProvider nameProvider;
    private final RuleLhsMatchWriter matchWriter;
    private final FunctionParams functionVars;
    private final VarContainer vars;


    /**
     * Whenever we see a variable more than once, instead of adding a variable declaration, we add a check that the two instances are equal.
     * This structure keeps track of that.
     */
    private final Set<KVariable> alreadySeenVariables;
    private final boolean startWithScopeBlockIfNecessary;

    private int kitemIndex = 0;

    public enum ExpressionType {IF, STATEMENT, NOTHING}

    private ExpressionType topExpressionType = null;
    private boolean containsIf = false;

    private void handleExpressionType(ExpressionType et) {
        if (topExpressionType == null) {
            topExpressionType = et;
            if (startWithScopeBlockIfNecessary && et != ExpressionType.IF) {
                sb.scopingBlock("scoping block, to avoid variable name collisions");
            }
        }
        if (et == ExpressionType.IF) {
            containsIf = true;
        }
    }

    public ExpressionType getTopExpressionType() {
        if (topExpressionType == null) {
            topExpressionType = ExpressionType.NOTHING;
        }
        return topExpressionType;
    }

    public boolean containsIf() {
        return containsIf;
    }

    public RuleLhsWriter(GoStringBuilder sb,
                         DefinitionData data,
                         GoNameProvider nameProvider,
                         RuleLhsMatchWriter matchWriter,
                         FunctionParams functionVars,
                         VarContainer vars,
                         Set<KVariable> alreadySeenVariables,
                         boolean startWithScopeBlockIfNecessary) {
        this.sb = sb;
        this.data = data;
        this.nameProvider = nameProvider;
        this.matchWriter = matchWriter;
        this.functionVars = functionVars;
        this.vars = vars;
        this.alreadySeenVariables = alreadySeenVariables;
        this.startWithScopeBlockIfNecessary = startWithScopeBlockIfNecessary;
    }

    private String nextSubject = null;
    private int nextFunctionVarIndex = 0;
    private KVariable nextAlias = null;

    private String consumeSubject() {
        if (nextSubject != null) {
            String subj = nextSubject;
            nextSubject = null;
            return subj;
        }
        if (nextFunctionVarIndex < functionVars.arity()) {
            String subj = functionVars.varName(nextFunctionVarIndex);
            nextFunctionVarIndex++;
            return subj;
        }
        return "BAD_SUBJ";
    }

    public void setNextSubject(String subj) {
        nextSubject = subj;
    }

    private KVariable consumeAlias() {
        if (nextAlias != null) {
            KVariable alias = nextAlias;
            nextAlias = null;
            return alias;
        }
        return null;
    }

    private void lhsTypeIf(String castVar, String subject, String castFunction) {
        handleExpressionType(ExpressionType.IF);
        sb.writeIndent();
        sb.append("if ").append(castVar).append(", t := i.Model.");
        sb.append(castFunction);
        sb.append("(").append(subject).append("); t");
    }

    @Override
    public void apply(KApply k) {
        if (k.klabel().name().equals("#KToken")) {
            assert k.klist().items().size() == 2;
            KToken ktoken = (KToken) k.klist().items().get(0);
            Sort sort = Outer.parseSort(ktoken.s());
            K value = k.klist().items().get(1);

            //magic down-ness
            handleExpressionType(ExpressionType.IF);
            sb.writeIndent();
            String subject = consumeSubject();
            sb.append("if ");
            matchWriter.appendKTokenMatch(sb, subject, nameProvider.sortVariableName(sort));
            sb.beginBlock("lhs KApply #KToken");
            nextSubject = subject;
            apply(value);
        } else if (k.klabel().name().equals("#Bottom")) {
            handleExpressionType(ExpressionType.IF);
            sb.writeIndent().append("if ");
            matchWriter.appendBottomMatch(sb, consumeSubject());
        } else if (data.functions.contains(k.klabel())) {
            if (data.collectionFor.containsKey(k.klabel())) {
                KLabel collectionLabel = data.collectionFor.get(k.klabel());
                Att attr = data.mainModule.attributesFor().apply(collectionLabel);
                if (attr.contains(Attribute.ASSOCIATIVE_KEY)
                        && !attr.contains(Attribute.COMMUTATIVE_KEY)
                        && !attr.contains(Attribute.IDEMPOTENT_KEY)) {

                    // list
                    handleExpressionType(ExpressionType.IF);
                    Sort sort = data.mainModule.sortFor().apply(collectionLabel);
                    if (k.items().size() == 0) {
                        // empty list
                        sb.writeIndent().append("if i.Model.IsEmptyList(");
                        sb.append(consumeSubject()).append(", ");
                        sb.append("m.").append(nameProvider.sortVariableName(sort)).append(", ");
                        sb.append("m.").append(nameProvider.klabelVariableName(collectionLabel));
                        sb.append(")");
                        sb.beginBlock("empty list ", ToKast.apply(k));
                    } else if (k.items().size() == 2) {
                        String headVar = "listHead" + kitemIndex;
                        String tailVar = "listTail" + kitemIndex;
                        kitemIndex++;
                        sb.writeIndent().append("if ok, ");
                        sb.append(headVar).append(", ").append(tailVar);
                        sb.append(" := i.Model.ListSplitHeadTail(");
                        sb.append(consumeSubject()).append(", ");
                        sb.append("m.").append(nameProvider.sortVariableName(sort)).append(", ");
                        sb.append("m.").append(nameProvider.klabelVariableName(collectionLabel));
                        sb.append("); ok");
                        sb.beginBlock("empty list ", ToKast.apply(k));

                        KApply headListElem = (KApply) k.items().get(0);
                        boolean isElement = attr.contains("element") && headListElem.klabel().equals(KORE.KLabel(attr.get("element")));
                        // boolean isWrapElement = !isElement && attr.contains("wrapElement") && kapp.klabel().equals(KORE.KLabel(attr.get("wrapElement")));
                        if (!isElement) {
                            throw KEMException.internalError("First argument of list cons a list element type");
                        }
                        if (headListElem.klist().size() != 1) {
                            throw KEMException.internalError("List element should only have 1 argument");
                        }
                        nextSubject = headVar;
                        apply(headListElem.items().get(0)); // not the element itself, but its contents

                        // tail
                        if (k.items().get(1) instanceof KApply) {
                            KApply tail = (KApply) k.items().get(1);
                            nextSubject = tailVar;
                            apply(tail);
                        } else if (k.items().get(1) instanceof KVariable) {
                            KVariable tail = (KVariable) k.items().get(1);
                            nextSubject = tailVar;
                            apply(tail);
                        } else {
                            throw KEMException.internalError("Second argument of list cons should be either a KApply of a KVariable, representing the tail");
                        }
                    } else {
                        throw KEMException.internalError("List KApply should be either of length 0 (empty list), or length 2 (head-tail)");
                    }
                }
            }
        } else {
            String subject = consumeSubject();
            KVariable alias = consumeAlias();
            int arity = k.klist().items().size();
            String kappVar;
            String aliasComment = "";
            if (alias != null) {
                kappVar = vars.lhsVars.getVarName(alias);
                aliasComment = " as " + alias.name();
            } else {
                kappVar = "kapp" + kitemIndex;
                kitemIndex++;
            }
            //String kappMV = vars.varIndexes.oneTimeVariableMVRef(kappVar);
            String kappMV = subject;

            handleExpressionType(ExpressionType.IF);
            //sb.appendIndentedLine(kappMV, " = ", subject);
            sb.writeIndent().append("if ");
            matchWriter.appendKApplyMatch(sb, kappMV, nameProvider.klabelVariableName(k.klabel()), arity);
            sb.beginBlock(ToKast.apply(k), aliasComment);
            int i = 0;
            for (K item : k.klist().items()) {
                String kappItemMV = vars.varIndexes.oneTimeVariableMVRef(kappVar + "Item" + i);
                sb.appendIndentedLine(kappItemMV, " = i.Model.KApplyArg(", kappMV, ", ", Integer.toString(i), ")");
                nextSubject = kappItemMV;
                apply(item);
                i++;
            }
        }
    }

    /**
     * Writes LHS for list contents, by recursively splitting it into head and tail
     */
    @Deprecated
    private void applyListContents(KApply k, String dataVarName, String originalListVar, Att attr) {
        if (k.items().size() == 0) {
            // empty list
            sb.writeIndent().append("if len(").append(dataVarName).append(") == 0");
            sb.beginBlock("empty list ", ToKast.apply(k));
        } else if (k.items().size() == 2) {
            // head
            sb.writeIndent().append("if len(").append(dataVarName).append(") >= 1");
            sb.beginBlock("list ", ToKast.apply(k));

            if (!(k.items().get(0) instanceof KApply)) {
                throw KEMException.internalError("First argument of list cons should be a KApply, representing the element");
            }
            KApply head = (KApply) k.items().get(0);
            boolean isElement = attr.contains("element") && head.klabel().equals(KORE.KLabel(attr.get("element")));
            // boolean isWrapElement = !isElement && attr.contains("wrapElement") && kapp.klabel().equals(KORE.KLabel(attr.get("wrapElement")));
            if (!isElement) {
                throw KEMException.internalError("First argument of list cons a list element type");
            }
            if (head.klist().size() != 1) {
                throw KEMException.internalError("List element should only have 1 argument");
            }
            sb.appendIndentedLine("// list head: ", ToKast.apply(head));
            nextSubject = dataVarName + "[0]";
            apply(head.klist().items().get(0));

            // tail
            if (k.items().get(1) instanceof KApply) {
                KApply tail = (KApply) k.items().get(1);
                String tailVar = "listDataTail" + kitemIndex;
                kitemIndex++;
                sb.appendIndentedLine(tailVar, " := ", dataVarName, "[1:] // list tail: ", ToKast.apply(tail));
                nextSubject = tailVar;
                applyListContents(tail, tailVar, originalListVar, attr);
            } else if (k.items().get(1) instanceof KVariable) {
                KVariable tail = (KVariable) k.items().get(1);
                String tailVar = "lisTail" + kitemIndex;
                kitemIndex++;
                sb.appendIndentedLine("var ", tailVar, " m.KReference");
                sb.appendIndentedLine(tailVar, " = &m.List { Sort: ", originalListVar, ".Sort, Label: ", originalListVar, ".Label, Data: ", originalListVar, ".Data[1:] }");
                // TODO: signal that nextSubject is already defined as list via a type enum (values: K, List, etc.)
                // and suppress type checking if type is already known
                nextSubject = tailVar;
                apply(tail);
            } else {
                throw KEMException.internalError("Second argument of list cons should be either a KApply of a KVariable, representing the tail");
            }
        }
    }

    void applyTuple(List<K> items) {
        for (K item : items) {
            apply(item);
        }
    }

    @Override
    public void apply(KAs k) {

        if (!(k.alias() instanceof KVariable)) {
            throw new IllegalArgumentException("KAs alias is not a KVariable.");
        }
        nextAlias = (KVariable) k.alias();
        apply(k.pattern());

        if (nextAlias != null) {
            throw new RuntimeException("KAs alias was not consumed. This scenario was not handled. An alias will be missing ");
        }
    }

    @Override
    public void apply(KRewrite k) {
        throw new AssertionError("unexpected rewrite");
    }

    @Override
    public void apply(KToken k) {
        handleExpressionType(ExpressionType.IF);
        sb.writeIndent();
        sb.append("if i.Model.Equals(").append(consumeSubject()).append(", ");
        RuleRhsWriterBase.appendKTokenRepresentation(sb, k, data, nameProvider);
        sb.append(")");
        sb.beginBlock(ToKast.apply(k));
    }

    @Override
    public void apply(KVariable k) {
        String varName = vars.varIndexes.kvariableMVRef(k);

        if (alreadySeenVariables.contains(k)) {
            handleExpressionType(ExpressionType.IF);
            sb.writeIndent();
            sb.append("if i.Model.Equals(").append(consumeSubject()).append(", ").append(varName).append(")");
            sb.beginBlock("lhs KVariable, which reappears:" + k.name());
            return;
        }
        alreadySeenVariables.add(k);

        Sort s = k.att().getOptional(Sort.class).orElse(KORE.Sort(""));
        if (data.mainModule.sortAttributesFor().contains(s)) {
            String hook = data.mainModule.sortAttributesFor().apply(s).<String>getOptional("hook").orElse("");
            if (GoBuiltin.PREDICATE_HOOKS.contains(hook)) {
                String subject = consumeSubject();
                handleExpressionType(ExpressionType.IF);
                sb.writeIndent().append("if ");
                matchWriter.appendPredicateMatch(hook, sb, subject, nameProvider.sortVariableName(s));
                sb.beginBlock("lhs KVariable with hook:" + hook);

                boolean varNeeded = vars.rhsVars.containsVar(k) // needed in RHS
                        || vars.lhsVars.getVarCount(k) > 1; // needed in LHS, when it reappears
                if (varNeeded) {
                    sb.appendIndentedLine(varName, " = ", subject, " // ", ToKast.apply((K) k));
                } else {
                    sb.appendIndentedLine("// unused variable: ", ToKast.apply((K) k));
                }
                return;
            }
        }

        if (varName == null) {
            handleExpressionType(ExpressionType.NOTHING);
            sb.writeIndent();
            sb.append("// varName=null").newLine();
        } else if (varName.equals("_")) {
            handleExpressionType(ExpressionType.NOTHING);
            sb.writeIndent();
            sb.append("// "); // no code here, it is redundant
            sb.append(varName).append(" = ").append(consumeSubject()).append(" // lhs KVariable _\n");
        } else {
            handleExpressionType(ExpressionType.STATEMENT);
            sb.writeIndent();
            sb.append(varName).append(" = ").append(consumeSubject()).append(" // lhs KVariable ").append(k.name()).newLine();
        }
    }

    @Override
    public void apply(KSequence k) {
        switch (k.items().size()) {
        case 0:
            sb.appendIndentedLine("// KSequence, size 0:", ToKast.apply(k));
            return;
        case 1:
            // no KSequence, go straight to the only item
            sb.appendIndentedLine("// KSequence, size 1:", ToKast.apply(k));
            apply(k.items().get(0));
            return;
        default:
            int nrHeads = k.items().size() - 1;
            String kseqVarName = vars.varIndexes.oneTimeVariableMVRef("kseq" + kitemIndex);
            kitemIndex++;

            // the subject might be an expression, call it only once, in the if
            handleExpressionType(ExpressionType.IF);
            sb.writeIndent().append("if ");
            //sb.append(kseqVarName).append(" = ").append(consumeSubject()).append("; ");
            String subject = consumeSubject();

            // match condition
            if (nrHeads == 1) {
                matchWriter.appendNonEmptyKSequenceMatch(sb, subject);
            } else {
                matchWriter.appendNonEmptyKSequenceMinLengthMatch(sb, subject, nrHeads);
            }
            sb.beginBlock(ToKast.apply(k));


            // declare head(s)/tails
            String kseqTail = "";
            String[] headMVRefs = new String[nrHeads];
            for (int i = 0; i < nrHeads; i++) {
                // split into head :: tail, if subject is KSequence; subject :: emptySequence otherwise
                // if multiple heads required, split repeatedly
                headMVRefs[i] = vars.varIndexes.oneTimeVariableMVRef(kseqVarName + "Head" + i);
                kseqTail = vars.varIndexes.oneTimeVariableMVRef(kseqVarName + "Tail" + i);
                sb.writeIndent().append("_, ");
                sb.append(headMVRefs[i]).append(", ");
                sb.append(kseqTail).append(" = i.Model.KSequenceSplitHeadTail(").append(subject).append(") // ");
                sb.append(ToKast.apply(k.items().get(i))).append(" ~> ...");
                sb.newLine();

                // the tail becomes the next subject to split
                subject = kseqTail;
            }

            // process heads
            for (int i = 0; i < nrHeads; i++) {
                nextSubject = headMVRefs[i];
                apply(k.items().get(i));
            }

            // process tail
            nextSubject = kseqTail;
            apply(k.items().get(k.items().size() - 1));

            return;
        }
    }

    @Override
    public void apply(InjectedKLabel k) {
        lhsTypeIf("ikl", consumeSubject(), "CastInjectedKLabel");
        sb.append(" && ikl.Label == m.").append(nameProvider.klabelVariableName(k.klabel()));
        sb.beginBlock();
    }

}