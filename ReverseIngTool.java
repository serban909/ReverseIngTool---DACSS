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
    private final List<String> methods;
    private final List<String> fields;

    public DiagramElement(String name, boolean isInterface)
    {
        this.name=name;
        this.isInterface=isInterface;
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

    public String toYumlString()
    {
        StringBuilder sb=new StringBuilder();
        sb.append("[");
        if(isInterface) sb.append("<<interface>>;");
        sb.append(name);
        if(!fields.isEmpty() && !methods.isEmpty()) sb.append("|");
        sb.append(String.join(";", fields));
        if(!fields.isEmpty() && !methods.isEmpty()) sb.append(";");
        sb.append(String.join(";", methods));
        sb.append("]");
        return sb.toString();
    }

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

    public String toYumlString()
    {
        String arrow;
        switch(type)
        {
            case EXTENDS:
                arrow= "^-";
                break;
            case IMPLEMENTS:
                arrow= "^-.-";
                break;
            case ASSOCIATION:
                arrow= "->";
                break;
            default:
                throw new RuntimeException("Unexpected relationship type");
        };

        return "["+from + "]" + arrow + "[" + to+ "]";
    }

    public String toPlantUmlString()
    {
        String arrow;
        switch (type) 
        {
            case EXTENDS:
                arrow= " <|-- ";
                break;
            case IMPLEMENTS:
                arrow= " <|.. ";
                break;
            case ASSOCIATION:
                arrow=" --> ";
                break;
            default:
                throw new RuntimeException("Unexpected relationship type");
        };

        return from + arrow + to + "\n";
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
        List<Relationship> relationships = new ArrayList<>();

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
                    if(!shouldIgnore(fieldType))
                    {
                        String assocTo=fullyQualifiedNames ? fieldType.getName():fieldType.getSimpleName();
                        relationships.add(new Relationship(name, assocTo, Relationship.Type.ASSOCIATION));
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
                                    relationships.add(new Relationship(name, assocTo, Relationship.Type.ASSOCIATION));
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

                    for(Class<?> paramType : method.getParameterTypes())
                    {
                        if(!shouldIgnore(paramType))
                        {
                            String assocTo=fullyQualifiedNames ? paramType.getName():paramType.getSimpleName();
                            relationships.add(new Relationship(name, assocTo, Relationship.Type.ASSOCIATION));
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
                relationships.add(new Relationship(name, superName, Relationship.Type.EXTENDS));
            }

            for(Class<?> interfaze : clazz.getInterfaces())
            {
                if(!shouldIgnore(interfaze))
                {
                    String interfazeName= fullyQualifiedNames ? interfaze.getName():interfaze.getSimpleName();
                    relationships.add(new Relationship(name, interfazeName, Relationship.Type.IMPLEMENTS));
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
            .map(DiagramElement::toYumlString)
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
            sb.append(element.toPlantUml());
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
