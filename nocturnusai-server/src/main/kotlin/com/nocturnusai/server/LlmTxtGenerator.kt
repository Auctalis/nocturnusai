package com.nocturnusai.server

import io.ktor.server.application.*
import io.ktor.server.routing.*
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.elementNames
import kotlin.reflect.KClass
import kotlin.reflect.KType

object LlmTxtGenerator {

    // Helper to register classes to document
    private val documentedClasses = listOf(
        FactRequest::class,
        RuleRequest::class,
        AtomDto::class,
        TemplateRequest::class,
        ExecuteRequest::class,
        CreateDbRequest::class,
        CreateTenantRequest::class
    )

    fun generate(application: Application): String {
        val sb = StringBuilder()
        
        // Load Verbose Introduction from Resources
        val resourceStream = this::class.java.classLoader.getResourceAsStream("llm_intro.md")
        if (resourceStream != null) {
            sb.append(resourceStream.bufferedReader().use { it.readText() })
            sb.append("\n\n") 
        } else {
            // Fallback if file missing
            sb.append("# Logic Server API Reference\n")
            sb.append("> **Warning**: Static context file 'llm_intro.md' not found.\n\n")
        }

        // Schemas
        sb.append("## Dynamic Data Schemas\n")
        sb.append("> These schemas are generated reflectively from the running server code.\n\n")
        
        documentedClasses.forEach { kClass ->
            try {
                // We need to fetch the serializer dynamically. 
                // Since we know these are @Serializable, strictly we can use serializer(Type)
                // But simplified: we assume we can get it via standard serializer() for the type.
                // However, without a reified type parameter, accessing serializer() for KClass is tricky in pure common code 
                // without InternalSerializationApi or casting.
                // We will use a wrapper helper that uses basic reflection or just manual printing if needed.
                // Actually, serializer(KType) is available.
                val serializer = serializer(kClass.java)
                sb.append(generateSchema(kClass.simpleName ?: "Unknown", serializer.descriptor))
            } catch (e: Exception) {
                sb.append("<!-- Failed to generate schema for ${kClass.simpleName}: ${e.message} -->\n")
            }
        }

        // Endpoints
        sb.append("## Dynamic Endpoints\n")
        sb.append("> These endpoints are discovered from the active Ktor routing tree.\n\n")
        
        val root = application.plugin(Routing)
        val routes = getAllRoutes(root)
        
        // Group/Sort routes
        routes.sortedBy { it }.forEach { routeLine ->
             sb.append(routeLine).append("\n\n")
        }

        return sb.toString()
    }

    private fun generateSchema(name: String, descriptor: SerialDescriptor): String {
        val sb = StringBuilder()
        sb.append("### `$name`\n")
        sb.append("```json\n")
        sb.append("{\n")
        
        val lines = mutableListOf<String>()
        for (i in 0 until descriptor.elementsCount) {
             val elementName = descriptor.getElementName(i)
             val elementDescriptor = descriptor.getElementDescriptor(i)
             val typeName = getTypeName(elementDescriptor)
             lines.add("  \"$elementName\": \"$typeName\"")
        }
        sb.append(lines.joinToString(",\n"))
        sb.append("\n}\n```\n")
        return sb.toString()
    }
    
    private fun getTypeName(descriptor: SerialDescriptor): String {
        return when (descriptor.kind) {
            is PrimitiveKind.STRING -> "String"
            is PrimitiveKind.BOOLEAN -> "Boolean"
            is PrimitiveKind.INT, PrimitiveKind.LONG -> "Number"
            is SerialKind.ENUM -> "Enum<${descriptor.serialName}>"
            else -> {
                if (descriptor.serialName.startsWith("kotlin.collections.List")) "List"
                else if (descriptor.serialName.startsWith("kotlin.collections.Map")) "Map"
                else descriptor.serialName.substringAfterLast('.')
            }
        }
    }

    private fun getAllRoutes(root: Route, parentPath: String = ""): List<String> {
        val results = mutableListOf<String>()
        
        // Check if this node has a selector (path segment or method)
        val selector = root.selector
        var currentPath = parentPath
        var method = ""
        
        if (selector is PathSegmentConstantRouteSelector) {
             currentPath = if (currentPath.endsWith("/")) currentPath + selector.value 
                           else "$currentPath/${selector.value}"
        } else if (selector is PathSegmentParameterRouteSelector) {
             currentPath = if (currentPath.endsWith("/")) currentPath + "{${selector.name}}"
                           else "$currentPath/{${selector.name}}"
        } else if (selector is HttpMethodRouteSelector) {
             method = selector.method.value
        }
        
        // If we found a method, this is likely an endpoint
        if (method.isNotEmpty()) {
             results.add("#### `$method $currentPath`")
        }
        
        // Recursively check children
        for (child in root.children) {
            results.addAll(getAllRoutes(child, currentPath))
        }
        
        return results
    }
}
