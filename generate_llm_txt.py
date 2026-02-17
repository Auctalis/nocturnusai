
import re
import os

# Configuration
SOURCE_FILES = [
    'nocturnusai-server/src/main/kotlin/com/nocturnusai/server/Application.kt',
    'nocturnusai-server/src/main/kotlin/com/nocturnusai/server/TemplateService.kt'
]
OUTPUT_FILE = 'llm.txt'

def parse_data_classes(content):
    """
    Extracts Kotlin data classes to build a schema reference.
    Returns a dict: {ClassName: {Field: Type}}
    """
    classes = {}
    # Regex for simple data class definition: data class Name(val x: Type, ...)
    # This is a basic parser and might need refinement for complex nested types
    class_regex = re.compile(r'data class (\w+)\((.*?)\)', re.DOTALL)
    
    matches = class_regex.findall(content)
    for name, fields_block in matches:
        fields = {}
        # Clean up newlines
        fields_block = fields_block.replace('\n', ' ').strip()
        # Split by comma, handling generic types might be tricky but let's try simple split
        # distinct field definitions
        arg_parts = [x.strip() for x in fields_block.split(',')]
        
        for arg in arg_parts:
            # val name: Type = Default
            if ':' in arg:
                parts = arg.split(':')
                field_name = parts[0].replace('val', '').replace('var', '').strip()
                type_def = parts[1].split('=')[0].strip() # Ignore default value
                fields[field_name] = type_def
        
        classes[name] = fields
    return classes

def parse_routes(content):
    """
    Extracts Ktor routes (GET, POST, etc.)
    Returns list of dicts: {method, path, body_type, description}
    """
    routes = []
    lines = content.split('\n')
    
    # Simple state machine
    current_comment = []
    
    # Regex to catch route start: post("/path") {
    route_regex = re.compile(r'(post|get|delete|put|patch)\s*\(\"([^\"]+)\"\)')
    # Regex to catch body receive: call.receive<Type>()
    receive_regex = re.compile(r'call\.receive<(\w+)>\(\)')
    
    for i, line in enumerate(lines):
        line_stripped = line.strip()
        
        # Capture Comments
        if line_stripped.startswith('//'):
            comment = line_stripped[2:].strip()
            # specific filtering to avoid code comments
            if not comment.startswith('TODO') and not comment.startswith('FIXME'):
                current_comment.append(comment)
            continue
        
        # Check for Route
        match = route_regex.search(line_stripped)
        if match:
            method = match.group(1).upper()
            path = match.group(2)
            
            # Scan ahead a few lines (e.g., 20) to find the request body type
            body_type = None
            for j in range(1, 40): # Look ahead window
                if i + j >= len(lines): break
                look_ahead = lines[i+j]
                
                # Stop if we hit another route (heuristic)
                if route_regex.search(look_ahead):
                    break
                    
                body_match = receive_regex.search(look_ahead)
                if body_match:
                    body_type = body_match.group(1)
                    break
            
            description = " ".join(current_comment) if current_comment else "No description available."
            
            routes.append({
                'method': method,
                'path': path,
                'body_type': body_type,
                'description': description
            })
            
            current_comment = [] # Reset
            
        elif line_stripped == '' or line_stripped.startswith('}'):
             # Reset comments on empty blocks or closures to avoid stale comments attaching to next route
             if not line_stripped.startswith('//'): 
                 pass 
             else:
                 current_comment = []
        else:
             # If line is code and not a route, drop comments? 
             # Heuristic: Closely attached comments only
             pass

    return routes

def generate_markdown(routes, classes):
    md = []
    md.append("# Logic Server API Reference\n")
    md.append("> **Auto-Generated**: This file is dynamically generated from the source code. Do not edit manually.\n")
    md.append("## Overview\n")
    md.append("This API allows interaction with the NocturnusAI Logic Server for asserting facts, rules, and performing queries.\n")
    
    md.append("\n## Data Schemas\n")
    for cls_name, fields in classes.items():
        # Filter unrelated classes if needed, or show all
        if "Request" in cls_name or "Dto" in cls_name:
            md.append(f"### `{cls_name}`")
            md.append("```json")
            md.append("{")
            item_strs = []
            for f, t in fields.items():
                item_strs.append(f'  "{f}": "{t}"')
            md.append(",\n".join(item_strs))
            md.append("}")
            md.append("```\n")

    md.append("\n## Endpoints\n")
    
    # Group by basic category (heuristic based on path)
    md.append("### Core Logic\n")
    
    for r in routes:
        md.append(f"#### `{r['method']} {r['path']}`")
        if r['description']:
            md.append(f"{r['description']}\n")
        
        if r['body_type']:
            md.append(f"- **Request Body**: `{r['body_type']}`")
        
        md.append("\n---\n")

    return "\n".join(md)

def main():
    classes = {}
    routes = []
    
    for source_file in SOURCE_FILES:
        if not os.path.exists(source_file):
            print(f"Warning: Source file {source_file} not found.")
            continue

        with open(source_file, 'r') as f:
            content = f.read()

        # Merge found classes
        c = parse_data_classes(content)
        classes.update(c)
        
        # Merge found routes
        r = parse_routes(content)
        routes.extend(r)
    
    markdown_output = generate_markdown(routes, classes)
    
    with open(OUTPUT_FILE, 'w') as f:
        f.write(markdown_output)
    
    print(f"Successfully generated {OUTPUT_FILE} with {len(routes)} endpoints and {len(classes)} schemas.")

if __name__ == "__main__":
    main()
