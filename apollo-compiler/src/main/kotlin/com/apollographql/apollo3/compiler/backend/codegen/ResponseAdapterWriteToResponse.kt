package com.apollographql.apollo3.compiler.backend.codegen

import com.apollographql.apollo3.api.ResponseAdapterCache
import com.apollographql.apollo3.api.json.JsonWriter
import com.apollographql.apollo3.compiler.applyIf
import com.apollographql.apollo3.compiler.backend.ast.CodeGenerationAst
import com.apollographql.apollo3.compiler.backend.codegen.Identifier.responseAdapterCache
import com.apollographql.apollo3.compiler.backend.codegen.Identifier.toResponse
import com.apollographql.apollo3.compiler.backend.codegen.Identifier.value
import com.apollographql.apollo3.compiler.backend.codegen.Identifier.writer
import com.apollographql.apollo3.compiler.escapeKotlinReservedWord
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.asTypeName
import com.squareup.kotlinpoet.joinToCode

internal fun CodeGenerationAst.ObjectType.writeToResponseFunSpec(generateFragmentsAsInterfaces: Boolean): FunSpec {
  return when (this.kind) {
    is CodeGenerationAst.ObjectType.Kind.ObjectWithFragments -> {
      if (generateFragmentsAsInterfaces) writeStreamedPolymorphicObjectFunSpec() else writeBufferedPolymorphicObjectFunSpec()
    }
    is CodeGenerationAst.ObjectType.Kind.FragmentDelegate -> writeFragmentDelegateToResponseFunSpec()
    else -> writeObjectToResponseFunSpec()
  }
}

private fun CodeGenerationAst.ObjectType.writeObjectToResponseFunSpec(): FunSpec {
  return FunSpec.builder(toResponse)
      .applyIf(!isShape) { addModifiers(KModifier.OVERRIDE) }
      .addParameter(writer, JsonWriter::class.asTypeName())
      .addParameter(responseAdapterCache, ResponseAdapterCache::class)
      .addParameter(value, this.typeRef.asTypeName())
      .addCode(this.fields.writeCode())
      .build()
}

private fun CodeGenerationAst.ObjectType.writeFragmentDelegateToResponseFunSpec(): FunSpec {
  val fragmentRef = (this.kind as CodeGenerationAst.ObjectType.Kind.FragmentDelegate).fragmentTypeRef
  return FunSpec.builder(toResponse)
      .addModifiers(KModifier.OVERRIDE)
      .addParameter(writer, JsonWriter::class.asTypeName())
      .addParameter(responseAdapterCache, ResponseAdapterCache::class)
      .addParameter(value, this.typeRef.asTypeName())
      .addStatement("%T.$toResponse($writer,·$responseAdapterCache,·$value.delegate)", fragmentRef.enclosingType!!.asAdapterTypeName())
      .build()
}

private fun CodeGenerationAst.ObjectType.writeStreamedPolymorphicObjectFunSpec(): FunSpec {
  val (defaultImplementation, possibleImplementations) = with(this.kind as CodeGenerationAst.ObjectType.Kind.ObjectWithFragments) {
    defaultImplementation to possibleImplementations
  }
  return toResponseFunSpecBuilder(this.typeRef.asTypeName())
      .applyIf(possibleImplementations.isEmpty()) {
        addCode(
            this@writeStreamedPolymorphicObjectFunSpec.fields.writeCode()
        )
      }
      .applyIf(possibleImplementations.isNotEmpty()) {
        beginControlFlow("when($value)")
        addCode(
            possibleImplementations.map { fragmentImplementation ->
              CodeBlock.of(
                  "is·%T·->·%T.$toResponse($writer,·$responseAdapterCache,·$value)",
                  fragmentImplementation.typeRef.asTypeName(),
                  fragmentImplementation.typeRef.asAdapterTypeName(),
              )
            }.joinToCode(separator = "\n", suffix = "\n")
        )
        addStatement(
            "is·%T·->·%T.$toResponse($writer,·$responseAdapterCache,·$value)",
            defaultImplementation!!.asTypeName(),
            defaultImplementation.asAdapterTypeName()
        )
        endControlFlow()
      }
      .build()
}

private fun CodeGenerationAst.ObjectType.writeBufferedPolymorphicObjectFunSpec(): FunSpec {
  val writeFieldsCode = this.fields
      .map { field -> field.writeCode() }
      .joinToCode(separator = "\n")

  val possibleImplementations = (this.kind as CodeGenerationAst.ObjectType.Kind.ObjectWithFragments).possibleImplementations

  val writeFragmentsCode = possibleImplementations.map { fragmentImplementation ->
    val propertyName = fragmentImplementation.typeRef.fragmentPropertyName()
    CodeBlock.of(
        "if·($value.%L·!=·null)·%T.$toResponse($writer,·$responseAdapterCache,·$value.%L)",
        propertyName,
        fragmentImplementation.typeRef.asAdapterTypeName(),
        propertyName,
    )
  }.joinToCode(separator = "\n", suffix = "\n")

  return toResponseFunSpecBuilder(typeRef.asTypeName())
      .addCode(writeFieldsCode)
      .addCode(writeFragmentsCode)
      .build()
}

internal fun List<CodeGenerationAst.Field>.writeCode(): CodeBlock {
  val builder = CodeBlock.builder()
  forEach {
    builder.add(it.writeCode())
  }
  return builder.build()
}

private fun CodeGenerationAst.Field.writeCode(): CodeBlock {
  return CodeBlock.builder().apply {
    addStatement("$writer.name(%S)", name)
    addStatement(
        "%L.$toResponse($writer, $responseAdapterCache, value.${name.escapeKotlinReservedWord()})",
        adapterInitializer(type, requiresBuffering)
    )
  }.build()
}

internal fun toResponseFunSpecBuilder(typeName: TypeName) = FunSpec.builder(toResponse)
    .addModifiers(KModifier.OVERRIDE)
    .addParameter(name = writer, type = JsonWriter::class.asTypeName())
    .addParameter(name = responseAdapterCache, type = ResponseAdapterCache::class)
    .addParameter(value, typeName)