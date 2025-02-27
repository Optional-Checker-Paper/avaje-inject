package io.avaje.inject.test;

import io.avaje.inject.BeanScope;
import io.avaje.inject.BeanScopeBuilder;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Qualifier;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.internal.configuration.plugins.Plugins;
import org.mockito.internal.util.reflection.GenericMaster;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

final class MetaReader {

  private final SetupMethods methodFinder;
  final List<Field> captors = new ArrayList<>();
  final List<FieldTarget> mocks = new ArrayList<>();
  final List<FieldTarget> spies = new ArrayList<>();
  final List<FieldTarget> injection = new ArrayList<>();
  final List<FieldTarget> staticMocks = new ArrayList<>();
  final List<FieldTarget> staticSpies = new ArrayList<>();
  final List<FieldTarget> staticInjection = new ArrayList<>();
  boolean classInjection;
  boolean instanceInjection;

  final Plugin plugin;
  boolean staticPlugin;
  boolean instancePlugin;

  MetaReader(Class<?> testClass, Plugin plugin) {
    this.plugin = plugin;
    this.methodFinder = new SetupMethods(testClass);
    for (Field field : testClass.getDeclaredFields()) {
      readField(field);
    }
  }

  boolean hasClassInjection() {
    return classInjection || methodFinder.hasStaticMethods();
  }

  boolean hasInstanceInjection() {
    return instanceInjection || methodFinder.hasInstanceMethods();
  }

  @Override
  public String toString() {
    String s = toStringAppend("mocks:", mocks);
    s += toStringAppend("spies:", spies);
    s += toStringAppend("inject:", injection);
    s += toStringAppend("captors:", captors);
    s += toStringAppend("staticMocks:", staticMocks);
    s += toStringAppend("staticSpies:", staticSpies);
    s += toStringAppend("staticInjection:", staticInjection);
    return s;
  }

  private String toStringAppend(String key, List<?> entries) {
    return entries.isEmpty() ? "" : key + entries + "; ";
  }

  private void readField(Field field) {
    final Mock mockAnnotation = field.getAnnotation(Mock.class);
    if (mockAnnotation != null) {
      add(newTarget(field), mocks, staticMocks);
      return;
    }
    final Spy spyAnnotation = field.getAnnotation(Spy.class);
    if (spyAnnotation != null) {
      add(newTarget(field), spies, staticSpies);
      return;
    }
    final Captor captorAnnotation = field.getAnnotation(Captor.class);
    if (captorAnnotation != null) {
      captors.add(field);
      return;
    }
    final Inject injectAnnotation = field.getAnnotation(Inject.class);
    if (injectAnnotation != null) {
      FieldTarget target = newTarget(field);
      if (plugin != null && plugin.forType(target.type())) {
        target.markForPluginInjection();
        if (target.isStatic()) {
          staticPlugin = true;
        } else {
          instancePlugin = true;
        }
      }
      add(target, injection, staticInjection);
    }
  }

  private void add(FieldTarget target, List<FieldTarget> instanceList, List<FieldTarget> staticList) {
    if (target.isStatic()) {
      classInjection = true;
      staticList.add(target);
    } else {
      instanceInjection = true;
      instanceList.add(target);
    }
  }

  private FieldTarget newTarget(Field field) {
    return new FieldTarget(field, name(field));
  }

  private String name(Field field) {
    final Named named = field.getAnnotation(Named.class);
    if (named != null) {
      return named.value().toLowerCase();
    }
    for (Annotation annotation : field.getAnnotations()) {
      for (Annotation metaAnnotation : annotation.annotationType().getAnnotations()) {
        if (metaAnnotation.annotationType().equals(Qualifier.class)) {
          return annotation.annotationType().getSimpleName().toLowerCase();
        }
      }
    }
    return null;
  }

  MetaInfo.Scope setFromScope(BeanScope beanScope, Object testInstance) {
    if (testInstance != null) {
      return setForInstance(beanScope, testInstance);
    } else {
      return setForStatics(beanScope);
    }
  }

  private MetaInfo.Scope setForInstance(BeanScope beanScope, Object testInstance) {
    try {
      Plugin.Scope pluginScope = instancePlugin ? plugin.createScope(beanScope) : null;

      for (Field field : captors) {
        set(field, captorFor(field), testInstance);
      }
      for (FieldTarget target : mocks) {
        target.setFromScope(beanScope, testInstance);
      }
      for (FieldTarget target : spies) {
        target.setFromScope(beanScope, testInstance);
      }
      for (FieldTarget target : injection) {
        if (target.pluginInjection) {
          Object instance = pluginScope.create(target.type());
          target.setFromPlugin(instance, testInstance);
        } else {
          target.setFromScope(beanScope, testInstance);
        }
      }
      return new MetaInfo.Scope(beanScope, pluginScope);

    } catch (IllegalAccessException e) {
      throw new RuntimeException(e);
    }
  }

  private MetaInfo.Scope setForStatics(BeanScope beanScope) {
    try {
      Plugin.Scope pluginScope = staticPlugin ? plugin.createScope(beanScope) : null;

      for (FieldTarget target : staticMocks) {
        target.setFromScope(beanScope, null);
      }
      for (FieldTarget target : staticSpies) {
        target.setFromScope(beanScope, null);
      }
      for (FieldTarget target : staticInjection) {
        if (target.pluginInjection) {
          Object instance = pluginScope.create(target.type());
          target.setFromPlugin(instance, null);
        } else {
          target.setFromScope(beanScope, null);
        }
      }
      return new MetaInfo.Scope(beanScope, pluginScope);
    } catch (IllegalAccessException e) {
      throw new RuntimeException(e);
    }
  }

  private Object captorFor(Field field) {
    Class<?> type = field.getType();
    if (!ArgumentCaptor.class.isAssignableFrom(type)) {
      throw new IllegalStateException("@Captor field must be of the type ArgumentCaptor.\n Field: '" + field.getName() + "' has wrong type");
    }
    Class<?> cls = new GenericMaster().getGenericType(field);
    return ArgumentCaptor.forClass(cls);
  }

  void build(BeanScopeBuilder builder, Object testInstance) {
    if (testInstance != null) {
      buildForInstance(builder, testInstance);
    } else {
      buildForStatics(builder);
    }
  }

  void buildForInstance(BeanScopeBuilder builder, Object testInstance) {
    for (FieldTarget target : mocks) {
      registerMock(testInstance, builder, target);
    }
    for (FieldTarget target : spies) {
      registerSpy(testInstance, builder, target);
    }
    for (FieldTarget target : injection) {
      Object existingValue = target.get(testInstance);
      if (existingValue != null) {
        registerAsTestDouble(builder, target, existingValue);
      }
    }
    methodFinder.invokeInstance(builder, testInstance);
  }

  void buildForStatics(BeanScopeBuilder builder) {
    for (FieldTarget target : staticMocks) {
      registerMock(null, builder, target);
    }
    for (FieldTarget target : staticSpies) {
      registerSpy(null, builder, target);
    }
    for (FieldTarget target : staticInjection) {
      Object existingValue = target.get(null);
      if (existingValue != null) {
        registerAsTestDouble(builder, target, existingValue);
      }
    }
    methodFinder.invokeStatics(builder);
  }

  private static void registerMock(Object testInstance, BeanScopeBuilder builder, FieldTarget target) {
    Object existingValue = target.get(testInstance);
    if (existingValue != null) {
      registerAsTestDouble(builder, target, existingValue);
    } else {
      builder.forTesting().mock(target.type(), target.name());
    }
  }

  private static void registerSpy(Object testInstance, BeanScopeBuilder builder, FieldTarget target) {
    Object existingValue = target.get(testInstance);
    if (existingValue != null) {
      registerAsTestDouble(builder, target, existingValue);
    } else {
      builder.forTesting().spy(target.type(), target.name());
    }
  }

  private static void registerAsTestDouble(BeanScopeBuilder builder, FieldTarget target, Object value) {
    target.markAsProvided();
    builder.bean(target.name(), target.type(), value);
  }

  void set(Field field, Object val, Object testInstance) throws IllegalAccessException {
    Plugins.getMemberAccessor().set(field, testInstance, val);
  }

  class FieldTarget {

    private final Field field;
    private final String name;
    private final boolean isStatic;
    private boolean pluginInjection;
    private boolean valueAlreadyProvided;

    FieldTarget(Field field, String name) {
      this.field = field;
      this.isStatic = Modifier.isStatic(field.getModifiers());
      this.name = name;
    }

    @Override
    public String toString() {
      return field.getName();
    }

    Class<?> type() {
      return field.getType();
    }

    String name() {
      return name;
    }

    boolean isStatic() {
      return isStatic;
    }

    Object get(Object instance) {
      try {
        return Plugins.getMemberAccessor().get(field, instance);
      } catch (IllegalAccessException e) {
        throw new RuntimeException(e);
      }
    }

    void setFromScope(BeanScope beanScope, Object testInstance) throws IllegalAccessException {
      if (!valueAlreadyProvided) {
        set(field, beanScope.get(type(), name), testInstance);
      }
    }

    void setFromPlugin(Object value, Object testInstance) throws IllegalAccessException {
      set(field, value, testInstance);
    }

    void markForPluginInjection() {
      pluginInjection = true;
    }

    void markAsProvided() {
      valueAlreadyProvided = true;
    }
  }

}
