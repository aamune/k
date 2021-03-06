// Copyright (c) 2015-2019 K Team. All Rights Reserved.
package org.kframework.backend.go.codegen;

import com.google.common.collect.Sets;
import org.kframework.backend.go.gopackage.GoPackageManager;
import org.kframework.backend.go.model.DefinitionData;
import org.kframework.backend.go.strings.GoNameProvider;
import org.kframework.backend.go.strings.GoStringBuilder;
import org.kframework.kore.KLabel;

public class EvalFunctionGen {

    private final DefinitionData data;
    private final GoPackageManager packageManager;
    private final GoNameProvider nameProvider;

    public EvalFunctionGen(DefinitionData data, GoPackageManager packageManager, GoNameProvider nameProvider) {
        this.data = data;
        this.packageManager = packageManager;
        this.nameProvider = nameProvider;
    }

    public String generate() {
        GoStringBuilder sb = new GoStringBuilder();
        sb.append("package ").append(packageManager.interpreterPackage.getName()).append("\n\n");

        sb.append("import (\n");
        sb.append("\tm \"").append(packageManager.modelPackage.getGoPath()).append("\"\n");
        sb.append(")\n\n");

        sb.append("const topCellInitializer m.KLabel = m.");
        sb.append(nameProvider.klabelVariableName(data.topCellInitializer));
        sb.append("\n\n");

        sb.append("func eval(c m.K, config m.K) (m.K, error)").beginBlock();
        sb.writeIndent().append("kapp, isKapply := c.(*m.KApply)\n");
        sb.writeIndent().append("if !isKapply").beginBlock();
        sb.writeIndent().append("return c, nil").newLine();
        sb.endOneBlock();
        sb.writeIndent().append("switch kapp.Label").beginBlock();
        for (KLabel label : Sets.union(data.functions, data.anywhereKLabels)) {
            sb.writeIndent().append("case m.");
            sb.append(nameProvider.klabelVariableName(label));
            sb.append(":").newLine().increaseIndent();

            // arity check
            int arity = data.functionParams.get(label).arity();
            sb.writeIndent().append("if len(kapp.List) != ").append(arity).beginBlock();
            sb.writeIndent().append("return m.NoResult, &evalArityViolatedError{funcName:\"");
            sb.append(nameProvider.evalFunctionName(label));
            sb.append("\", expectedArity: ").append(arity);
            sb.append(", actualArity: len(kapp.List)}").newLine();
            sb.endOneBlock();

            // function call
            sb.writeIndent().append("return ");
            sb.append(nameProvider.evalFunctionName(label));
            sb.append("(");
            for (int i = 0; i < arity; i++) {
                sb.append("kapp.List[").append(i).append("], ");
            }
            sb.append("config, -1)").newLine();
            sb.decreaseIndent();
        }
        sb.writeIndent().append("default:").newLine().increaseIndent();
        sb.writeIndent().append("return c, nil").newLine();
        sb.decreaseIndent();
        sb.endOneBlock();
        sb.endOneBlock();
        sb.newLine();

        return sb.toString();
    }
}
