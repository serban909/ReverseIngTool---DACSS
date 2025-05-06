import java.io.FileWriter;
import java.io.PrintWriter;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import java.util.*;

interface DiagramFormatter
{
    public String format(List<DiagramElement> elements);
    public String getFileExtension();
}

class DiagramElement
{
    private final String name;
    private final boolean isInterface;
    private final boolean isRelationship;
    private final Relationship relationship;
    private final List<String> methods;
    private final List<String> fields;

    public DiagramElement(String name, boolean isInterface)
    {
        this.name=name;
        this.isInterface=isInterface;
        this.isRelationship=false;
        this.relationship=null;
        this.methods=new ArrayList<>();
        this.fields=new ArrayList<>();
    }

    public DiagramElement(Relationship relationship)
    {
        this.name=null;
        this.isInterface=false;
        this.isRelationship=true;
        this.relationship=relationship;
        this.methods=new ArrayList<>();
        this.fields=new ArrayList<>();
    }

    public void addField(String field)
    {
        fields.add(field);
    }

    public void addMethod(String method)
    {
        methods.add(method);
    }

    public String getName()
    {
        return name;
    }

    public boolean isRelationship()
    {
        return isRelationship;
    }

    public Relationship getRelationship()
    {
        return relationship;
    }

    public String toYumlString() 
    {
        StringBuilder sb = new StringBuilder();
        sb.append("[");
        if (isInterface) sb.append("<<interface>>;");
        sb.append(name);
    
        if (!fields.isEmpty() || !methods.isEmpty()) sb.append("|");
    
        if (!fields.isEmpty()) 
        {
            sb.append(String.join(";", fields));
        }
    
        if (!methods.isEmpty()) 
        {
            sb.append("|");
            sb.append(String.join(";", methods));
        }
    
        sb.append("]");
        return sb.toString();
    }
    
    // separat factory ul de formate de relatii si exportarea de relatii, caz getComponent pt array si liste
    // nu se ocupa relatia de format si relatia sa fie de diagram element nu de string
    // diagram element contine si relatia
    // factory ul sa fie cel care se ocupa de formatare si relatia sa nu contina tip de format
    public String toPlantUml()
    {
        StringBuilder sb=new StringBuilder();
        sb.append(isInterface ? "interface " : "class ").append(name).append("{\n");
        for(String field: fields) sb.append("  ").append(field).append("\n");
        for(String method: methods) sb.append("  ").append(method).append("\n");
        sb.append("}\n");
        return sb.toString();
    }
}

class JarClassLoader
{
    private final String jarPath;

    public JarClassLoader(String jarPath)
    {
        this.jarPath = jarPath;
    }

    public List<Class<?>> loadAllClasses() throws Exception
    {
        List<Class<?>> classes = new ArrayList<>();
        try (JarFile jarFile = new JarFile(jarPath)) 
        {
            Enumeration<JarEntry> entries = jarFile.entries();

            URL[] urls = { new URL("jar:file:" + jarPath+"!/") };
            URLClassLoader urlClassLoader = URLClassLoader.newInstance(urls);

            while(entries.hasMoreElements())
            {
                JarEntry entry = entries.nextElement();
                if(entry.getName().endsWith(".class"))
                {
                    String className=entry.getName()
                        .replace(".class", "")
                        .replace("/", ".");
                    try
                    {
                        classes.add(urlClassLoader.loadClass(className));
                    }
                    catch(Throwable ignored)
                    {

                    }
                }
            }
        }
        return classes;
    }
}

class FormatterFactory
{
    public static DiagramFormatter getFormatter(String name)
    {
        switch (name.toLowerCase()) {
            case "yuml":
                return new YumlFormatter();
            case "plantuml":
                return new PlantUmlFormatter();
            default:
                return null;
        }
    }
}

class Relationship
{
    public enum Type {
        ASSOCIATION, EXTENDS, IMPLEMENTS
    };

    public final String from;
    public final String to;
    public final Type type;

    public Relationship(String from, String to, Type type) 
    {
        this.from = from;
        this.to = to;
        this.type=type;
    }
}

class ClassAnalyzer
{
    private final List<Class<?>> classes;
    private final Set<String> ignorePrefixes;
    private final boolean showMethods;
    private final boolean showFields;
    private final boolean fullyQualifiedNames;

    public ClassAnalyzer(List<Class<?>> classes, Set<String> ignorePrefixes, boolean showMethods, boolean showFields, boolean fullyQualifiedNames)
    {
        this.classes = classes;
        this.ignorePrefixes=ignorePrefixes;
        this.showMethods=showMethods;
        this.showFields=showFields;
        this.fullyQualifiedNames=fullyQualifiedNames;
    }

    public List<DiagramElement> analyze()
    {
        List<DiagramElement> elements = new ArrayList<>();
        Map<String, DiagramElement> elementMap = new HashMap<>();

        for( Class<?> clazz : classes )
        {
            if(shouldIgnore(clazz)) continue;

            String name= fullyQualifiedNames ? clazz.getName() : clazz.getSimpleName();
            boolean isInterface = clazz.isInterface();
            DiagramElement element = new DiagramElement(name, isInterface);

            if(showFields)
            {
                for(Field field : clazz.getDeclaredFields())
                {
                    element.addField("+"+field.getName()+":"+field.getType().getSimpleName());

                    Class<?> fieldType=field.getType();

                    if(fieldType.isArray())
                    {
                        Class<?> componentType=fieldType.getComponentType();
                        if(!shouldIgnore(componentType))
                        {
                            String assocTo = fullyQualifiedNames ? componentType.getName() : componentType.getSimpleName();
                            elements.add(new DiagramElement(new Relationship(name, assocTo, Relationship.Type.ASSOCIATION)));
                        }
                    }
                    else
                    {
                        if(!shouldIgnore(fieldType))
                        {
                            String assocTo=fullyQualifiedNames ? fieldType.getName():fieldType.getSimpleName();
                            elements.add(new DiagramElement(new Relationship(name, assocTo, Relationship.Type.ASSOCIATION)));
                        }
                    }

                    Type genericType=field.getGenericType();
                    if(genericType instanceof ParameterizedType)
                    {
                        ParameterizedType pt = (ParameterizedType) genericType;
                        for(Type actualType : pt.getActualTypeArguments())
                        {
                            if(actualType instanceof Class<?>)
                            {
                                Class<?> actualClass = (Class<?>) actualType;
                                if(!shouldIgnore(actualClass))
                                {
                                    String assocTo=fullyQualifiedNames ? actualClass.getName():actualClass.getSimpleName();
                                    elements.add(new DiagramElement(new Relationship(name, assocTo, Relationship.Type.ASSOCIATION)));
                                }
                            }
                        }
                    }
                }
            }

            if(showMethods)
            {
                for(Method method : clazz.getDeclaredMethods())
                {
                    
                    StringBuilder methodSignature = new StringBuilder("+" + method.getName() + "(");
                    Class<?>[] paramTypes = method.getParameterTypes();

                    for (int i = 0; i < paramTypes.length; i++) 
                    {
                        methodSignature.append(paramTypes[i].getSimpleName());
                        if (i < paramTypes.length - 1) methodSignature.append(", ");
                    }

                    methodSignature.append("):" + method.getReturnType().getSimpleName());
                    element.addMethod(methodSignature.toString());

                    for (Class<?> paramType : paramTypes) 
                    {
                        Class<?> assocClass = paramType;
                        if (paramType.isArray()) 
                        {
                            assocClass = paramType.getComponentType();
                        }
                    
                        if (!shouldIgnore(assocClass)) 
                        {
                            String assocTo = fullyQualifiedNames ? assocClass.getName() : assocClass.getSimpleName();
                            elements.add(new DiagramElement(new Relationship(name, assocTo, Relationship.Type.ASSOCIATION)));
                        }
                    }
                    
                }
            }

            if(showMethods)
            {
                for(Constructor<?> constructor : clazz.getDeclaredConstructors())
                {
                    StringBuilder ctorSignature = new StringBuilder("+" + clazz.getSimpleName() + "(");
                    Class<?>[] paramTypes = constructor.getParameterTypes();
                    for (int i = 0; i < paramTypes.length; i++) 
                    {
                        ctorSignature.append(paramTypes[i].getSimpleName());
                        if (i < paramTypes.length - 1) ctorSignature.append(", ");
                    }
                    ctorSignature.append(")");
                    element.addMethod(ctorSignature.toString());
                }
            }

            Class<?> superClass = clazz.getSuperclass();
            if(superClass != null && !shouldIgnore(superClass) && !superClass.equals(Object.class))
            {
                String superName= fullyQualifiedNames ? superClass.getName():superClass.getSimpleName();
                elements.add(new DiagramElement(new Relationship(name, superName, Relationship.Type.EXTENDS)));
            }

            for(Class<?> interfaze : clazz.getInterfaces())
            {
                if(!shouldIgnore(interfaze))
                {
                    String interfazeName= fullyQualifiedNames ? interfaze.getName():interfaze.getSimpleName();
                    elements.add(new DiagramElement(new Relationship(name, interfazeName, Relationship.Type.IMPLEMENTS)));
                }
            }

            elements.add(element);
            elementMap.put(name, element);
        }

        return elements;
    }

    private boolean shouldIgnore(Class<?> clazz)
    {
        String name = clazz.getName();
        for(String prefix : ignorePrefixes)
        {
            if(name.startsWith(prefix))
            {
                return true;
            }
        }

        return false;
    }
}

class YumlFormatter implements DiagramFormatter
{
    public String format(List<DiagramElement> elements)
    {
        return elements.stream()
            .map(e ->
            {
                if(e.isRelationship())
                {
                    Relationship r=e.getRelationship();
                    String arrow;
                    switch (r.type) 
                    {
                        case EXTENDS:
                            arrow="^-";
                            break;
                        case IMPLEMENTS:
                            arrow="^-.-";
                            break;
                        default:
                            arrow="->";
                    }
                    return "[" + r.from + "]" + arrow + "[" + r.to + "]";
                }
                else
                {
                    return e.toYumlString();
                }
            })
            .collect(Collectors.joining(", "));
    }

    public String getFileExtension()
    {
        return "-yuml.txt";
    }
}

class PlantUmlFormatter implements DiagramFormatter
{
    public String format(List<DiagramElement> elements)
    {
        StringBuilder sb = new StringBuilder();
        sb.append("@startuml\n");

        for(DiagramElement element : elements)
        {
            if(element.isRelationship())
            {
                Relationship r=element.getRelationship();
                String arrow;
                switch(r.type)
                {
                    case EXTENDS: arrow=" <|-- "; break;
                    case IMPLEMENTS: arrow=" <|.. "; break;
                    default: arrow=" --> ";
                }
                sb.append(r.from).append(arrow).append(r.to).append("\n");
            }
            else
            {
                sb.append(element.toPlantUml());
            }
        }

        sb.append("@enduml\n");

        return sb.toString();
    }

    public String getFileExtension()
    {
        return "-plantuml.puml";
    }
}

public class ReverseIngTool 
{
    public static void main(String[] args) throws Exception 
    {
        if(args.length<2)
        {
            System.out.println("Usage: java Main <jar-file> <format> [options]");
            return;
        }

        String jarPath = args[0];
        String format = args[1];

        Set<String> ignorePrefixes = new HashSet<>();
        boolean showMethods=false;
        boolean showFields=false;
        boolean fullyQualifiedName=false;

        for(int i=2; i<args.length; i++)
        {
            if(args[i].startsWith("--ignore="))
            {
                String[] ignored= args[i].substring(9).split(", ");
                Collections.addAll(ignorePrefixes, ignored);
            }
            else if(args[i].equals("--showMethods"))
            {
                showMethods=true;
            }
            else if(args[i].equals("--showFields"))
            {
                showFields=true;
            }
            else if(args[i].equals("--fullyQualifiedName"))
            {
                fullyQualifiedName=true;
            }
        }

        JarClassLoader jarClassLoader = new JarClassLoader(jarPath);
        List<Class<?>> classes = jarClassLoader.loadAllClasses();

        ClassAnalyzer analyzer = new ClassAnalyzer(classes, ignorePrefixes, showMethods, showFields, fullyQualifiedName);
        List<DiagramElement> diagramElements = analyzer.analyze();

        DiagramFormatter formatter = FormatterFactory.getFormatter(format);
        if(formatter==null)
        {
            System.err.println("Unknown format: " + format);
            return;
        }

        String output=formatter.format(diagramElements);
        try(PrintWriter writer = new PrintWriter(new FileWriter(jarPath.replace(".jar", formatter.getFileExtension()))))
        {
            writer.write(output);
        }
        System.out.println("Diagram written to file.");
    }    
}
