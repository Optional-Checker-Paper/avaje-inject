package io.avaje.inject.generator;

import static io.avaje.inject.generator.ProcessingContext.getImportedAspect;

import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import java.util.*;

/**
 * Read points for field injection and method injection
 * on baseType plus inherited injection points.
 */
final class TypeExtendsInjection {

  private MethodReader injectConstructor;
  private final List<MethodReader> otherConstructors = new ArrayList<>();
  private final List<MethodReader> factoryMethods = new ArrayList<>();
  private final List<FieldReader> injectFields = new ArrayList<>();
  private final Map<String, MethodReader> injectMethods = new LinkedHashMap<>();
  private final Set<String> notInjectMethods = new HashSet<>();
  private final List<AspectMethod> aspectMethods = new ArrayList<>();
  private final Map<String, Integer> nameIndex = new HashMap<>();

  private final ImportTypeMap importTypes;
  private final TypeElement baseType;
  private final boolean factory;
  private final List<AspectPair> typeAspects;
  private Element postConstructMethod;
  private Element preDestroyMethod;
  private Integer preDestroyPriority;

  TypeExtendsInjection(TypeElement baseType, boolean factory, ImportTypeMap importTypes) {
    this.importTypes = importTypes;
    this.baseType = baseType;
    this.factory = factory;
    this.typeAspects = readAspects(baseType);
  }

  void read(TypeElement type) {
    for (Element element : type.getEnclosedElements()) {
      switch (element.getKind()) {
        case CONSTRUCTOR:
          readConstructor(element, type);
          break;
        case FIELD:
          readField(element);
          break;
        case METHOD:
          readMethod(element, type);
          break;
      }
    }
  }

  /** Read the annotations on the type. */
  List<AspectPair> readAspects(Element element) {
    final List<AspectPair> aspects = new ArrayList<>();
    for (final AnnotationMirror annotationMirror : element.getAnnotationMirrors()) {
      final var anElement = annotationMirror.getAnnotationType().asElement();
      final var aspect = AspectPrism.getInstanceOn(anElement);
      if (aspect != null) {
        aspects.add(new AspectPair(anElement, aspect.ordering()));
      } else {
        getImportedAspect(anElement.asType().toString())
            .ifPresent(p -> aspects.add(new AspectPair(anElement, p.ordering())));
      }
    }
    return aspects;
  }

  private void readField(Element element) {
    if (InjectPrism.isPresent(element)) {
      injectFields.add(new FieldReader(element));
    }
  }

  private void readConstructor(Element element, TypeElement type) {
    if (type != baseType) {
      // only interested in the top level constructors
      return;
    }

    ExecutableElement ex = (ExecutableElement) element;
    MethodReader methodReader = new MethodReader(ex, baseType, importTypes).read();

    if (InjectPrism.isPresent(element)) {
      injectConstructor = methodReader;
    } else {
      if (methodReader.isNotPrivate()) {
        otherConstructors.add(methodReader);
      }
    }
  }

  private void readMethod(Element element, TypeElement type) {
    boolean checkAspect = true;
    ExecutableElement methodElement = (ExecutableElement) element;
    if (factory) {
      BeanPrism bean = BeanPrism.getInstanceOn(element);
      if (bean != null) {
        addFactoryMethod(methodElement, bean);
        checkAspect = false;
      }
    }
    var inject = InjectPrism.isPresent(element);
    final String methodKey = methodElement.getSimpleName().toString();
    if (inject && !notInjectMethods.contains(methodKey)) {
      if (!injectMethods.containsKey(methodKey)) {
        MethodReader methodReader = new MethodReader(methodElement, type, importTypes).read();
        if (methodReader.isNotPrivate()) {
          injectMethods.put(methodKey, methodReader);
          checkAspect = false;
        }
      }
    } else {
      notInjectMethods.add(methodKey);
    }
    if (AnnotationUtil.hasAnnotationWithName(element, "PostConstruct")) {
      postConstructMethod = element;
      checkAspect = false;
    }
    if (AnnotationUtil.hasAnnotationWithName(element, "PreDestroy")) {
      preDestroyMethod = element;
      checkAspect = false;
      PreDestroyPrism.getOptionalOn(element).ifPresent(preDestroy -> preDestroyPriority = preDestroy.priority());
    }
    if (checkAspect) {
      checkForAspect(methodElement);
    }
  }

  private int methodNameIndex(String name) {
    Integer counter = nameIndex.get(name);
    if (counter == null) {
      nameIndex.put(name, 1);
      return 0;
    } else {
      nameIndex.put(name, counter + 1);
      return counter;
    }
  }

  private void checkForAspect(ExecutableElement methodElement) {
    Set<Modifier> modifiers = methodElement.getModifiers();
    if (modifiers.contains(Modifier.PRIVATE) || modifiers.contains(Modifier.STATIC) || modifiers.contains(Modifier.ABSTRACT)) {
      return;
    }
    int nameIndex = methodNameIndex(methodElement.getSimpleName().toString());
    List<AspectPair> aspectPairs = readAspects(methodElement);
    aspectPairs.addAll(typeAspects);

    if (!aspectPairs.isEmpty()) {
      aspectMethods.add(new AspectMethod(nameIndex, aspectPairs, methodElement));
    }
  }


  private void addFactoryMethod(ExecutableElement methodElement, BeanPrism bean) {
    String qualifierName = Util.getNamed(methodElement);
    factoryMethods.add(new MethodReader(methodElement, baseType, bean, qualifierName, importTypes).read());
  }

  BeanAspects hasAspects() {
    return aspectMethods.isEmpty() ? BeanAspects.EMPTY : new BeanAspects(aspectMethods);
  }

  void removeFromProvides(List<String> provides) {
    MethodReader constructor = constructor();
    if (constructor != null) {
      constructor.removeFromProvides(provides);
    }
    for (FieldReader injectField : injectFields) {
      injectField.removeFromProvides(provides);
    }
    for (MethodReader injectMethod : injectMethods.values()) {
      injectMethod.removeFromProvides(provides);
    }
  }

  List<FieldReader> injectFields() {
    List<FieldReader> list = new ArrayList<>(injectFields);
    Collections.reverse(list);
    return list;
  }

  List<MethodReader> injectMethods() {
    List<MethodReader> list = new ArrayList<>(injectMethods.values());
    Collections.reverse(list);
    return list;
  }

  List<MethodReader> factoryMethods() {
    return factoryMethods;
  }

  Element postConstructMethod() {
    return postConstructMethod;
  }

  Element preDestroyMethod() {
    return preDestroyMethod;
  }

  Integer preDestroyPriority() {
    return preDestroyPriority;
  }

  MethodReader constructor() {
    if (injectConstructor != null) {
      return injectConstructor;
    }
    if (otherConstructors.size() == 1) {
      return otherConstructors.get(0);
    }
    // check if there is only one public constructor
    List<MethodReader> allPublic = new ArrayList<>();
    for (MethodReader ctor : otherConstructors) {
      if (ctor.isPublic()) {
        allPublic.add(ctor);
      }
    }
    if (allPublic.size() == 1) {
      // fallback to the single public constructor
      return allPublic.get(0);
    }
    return null;
  }
}
