/*
 * Copyright (c) 2014, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.graal.hotspot.nodes;

import com.oracle.graal.api.meta.*;
import com.oracle.graal.compiler.common.*;
import com.oracle.graal.compiler.common.type.*;
import com.oracle.graal.graph.*;
import com.oracle.graal.graph.spi.*;
import com.oracle.graal.hotspot.*;
import com.oracle.graal.hotspot.HotSpotVMConfig.CompressEncoding;
import com.oracle.graal.hotspot.meta.*;
import com.oracle.graal.hotspot.nodes.type.*;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.calc.*;
import com.oracle.graal.nodes.spi.*;
import com.oracle.graal.nodes.type.*;

/**
 * Compress or uncompress an oop or metaspace pointer.
 */
@NodeInfo(nameTemplate = "{p#op/s}")
public final class CompressionNode extends ConvertNode implements LIRLowerable, Canonicalizable {

    private enum CompressionOp {
        Compress,
        Uncompress
    }

    private final CompressionOp op;
    private final CompressEncoding encoding;

    private CompressionNode(CompressionOp op, ValueNode input, CompressEncoding encoding) {
        super(mkStamp(op, input.stamp(), encoding), input);
        this.op = op;
        this.encoding = encoding;
    }

    public static CompressionNode compress(ValueNode input, CompressEncoding encoding) {
        return input.graph().unique(new CompressionNode(CompressionOp.Compress, input, encoding));
    }

    public static CompressionNode uncompress(ValueNode input, CompressEncoding encoding) {
        return input.graph().unique(new CompressionNode(CompressionOp.Uncompress, input, encoding));
    }

    private static Constant compress(Constant c, CompressEncoding encoding) {
        if (Constant.NULL_OBJECT.equals(c)) {
            return HotSpotCompressedNullConstant.COMPRESSED_NULL;
        } else if (c instanceof HotSpotObjectConstant) {
            return ((HotSpotObjectConstant) c).compress();
        } else if (c instanceof HotSpotMetaspaceConstant) {
            assert c.getKind() == Kind.Long;
            return HotSpotMetaspaceConstant.forMetaspaceObject(Kind.Int, encoding.compress(c.asLong()), HotSpotMetaspaceConstant.getMetaspaceObject(c));
        } else {
            throw GraalInternalError.shouldNotReachHere("invalid constant input for compress op: " + c);
        }
    }

    private static Constant uncompress(Constant c, CompressEncoding encoding) {
        if (HotSpotCompressedNullConstant.COMPRESSED_NULL.equals(c)) {
            return Constant.NULL_OBJECT;
        } else if (c instanceof HotSpotObjectConstant) {
            return ((HotSpotObjectConstant) c).uncompress();
        } else if (c instanceof HotSpotMetaspaceConstant) {
            assert c.getKind() == Kind.Int;
            return HotSpotMetaspaceConstant.forMetaspaceObject(Kind.Long, encoding.uncompress(c.asInt()), HotSpotMetaspaceConstant.getMetaspaceObject(c));
        } else {
            throw GraalInternalError.shouldNotReachHere("invalid constant input for uncompress op: " + c);
        }
    }

    @Override
    public Constant convert(Constant c) {
        switch (op) {
            case Compress:
                return compress(c, encoding);
            case Uncompress:
                return uncompress(c, encoding);
            default:
                throw GraalInternalError.shouldNotReachHere();
        }
    }

    @Override
    public Constant reverse(Constant c) {
        switch (op) {
            case Compress:
                return uncompress(c, encoding);
            case Uncompress:
                return compress(c, encoding);
            default:
                throw GraalInternalError.shouldNotReachHere();
        }
    }

    @Override
    public boolean isLossless() {
        return true;
    }

    private static Stamp mkStamp(CompressionOp op, Stamp input, CompressEncoding encoding) {
        switch (op) {
            case Compress:
                if (input instanceof ObjectStamp) {
                    // compressed oop
                    return NarrowOopStamp.compressed((ObjectStamp) input, encoding);
                } else if (input instanceof IntegerStamp) {
                    // compressed metaspace pointer
                    assert PrimitiveStamp.getBits(input) == 64;
                    return StampFactory.forInteger(32);
                }
                break;
            case Uncompress:
                if (input instanceof NarrowOopStamp) {
                    // oop
                    assert encoding.equals(((NarrowOopStamp) input).getEncoding());
                    return ((NarrowOopStamp) input).uncompressed();
                } else if (input instanceof IntegerStamp) {
                    // metaspace pointer
                    assert PrimitiveStamp.getBits(input) == 32;
                    return StampFactory.forInteger(64);
                }
                break;
        }
        throw GraalInternalError.shouldNotReachHere(String.format("Unexpected input stamp %s", input));
    }

    public CompressEncoding getEncoding() {
        return encoding;
    }

    @Override
    public Node canonical(CanonicalizerTool tool) {
        if (getInput().isConstant()) {
            return ConstantNode.forConstant(stamp(), evalConst(getInput().asConstant()), tool.getMetaAccess(), graph());
        } else if (getInput() instanceof CompressionNode) {
            CompressionNode other = (CompressionNode) getInput();
            if (op != other.op && encoding.equals(other.encoding)) {
                return other.getInput();
            }
        }
        return this;
    }

    @Override
    public void generate(NodeLIRBuilderTool gen) {
        HotSpotLIRGenerator hsGen = (HotSpotLIRGenerator) gen.getLIRGeneratorTool();
        boolean nonNull;
        if (getInput().stamp() instanceof ObjectStamp) {
            nonNull = StampTool.isObjectNonNull(getInput().stamp());
        } else {
            // metaspace pointers are never null
            nonNull = true;
        }

        Value result;
        switch (op) {
            case Compress:
                result = hsGen.emitCompress(gen.operand(getInput()), encoding, nonNull);
                break;
            case Uncompress:
                result = hsGen.emitUncompress(gen.operand(getInput()), encoding, nonNull);
                break;
            default:
                throw GraalInternalError.shouldNotReachHere();
        }
        gen.setResult(this, result);
    }
}