/*
 * Copyright (C) 2015 Sebastian Daschner, sebastian-daschner.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.sebastian_daschner.jaxrs_analyzer.analysis.results;

import static com.sebastian_daschner.jaxrs_analyzer.model.types.Types.COLLECTION;

import java.lang.reflect.Modifier;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javassist.CtClass;
import javassist.CtField;
import javassist.CtMethod;
import javassist.NotFoundException;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlTransient;

import com.sebastian_daschner.jaxrs_analyzer.LogProvider;
import com.sebastian_daschner.jaxrs_analyzer.analysis.utils.JavaUtils;
import com.sebastian_daschner.jaxrs_analyzer.model.Pair;
import com.sebastian_daschner.jaxrs_analyzer.model.rest.TypeIdentifier;
import com.sebastian_daschner.jaxrs_analyzer.model.rest.TypeRepresentation;
import com.sebastian_daschner.jaxrs_analyzer.model.types.Type;
import com.sebastian_daschner.jaxrs_analyzer.model.types.Types;

/**
 * Analyzes a class (usually a POJO) for it's properties and methods.
 * The analysis is used to derive the JSON/XML representations.
 *
 * @author Sebastian Daschner
 */
class JavaTypeAnalyzer {

    private final static String[] NAMES_TO_IGNORE = {"getClass"};

    /**
     * The type representation storage where all analyzed types have to be added. This will be created by the caller.
     */
    private final Map<TypeIdentifier, TypeRepresentation> typeRepresentations;
    private final Set<Type> analyzedTypes;

    JavaTypeAnalyzer(final Map<TypeIdentifier, TypeRepresentation> typeRepresentations) {
        this.typeRepresentations = typeRepresentations;
        analyzedTypes = new HashSet<>();
    }

    /**
     * Analyzes the given type. Resolves known generics and creates a representation of the contained class, all contained properties
     * and nested types recursively.
     *
     * @param rootType The type to analyze
     * @return The (root) type identifier
     */
    // TODO consider arrays
    TypeIdentifier analyze(final Type rootType) {
        final Type type = ResponseTypeNormalizer.normalizeResponseWrapper(rootType);
        final TypeIdentifier identifier = TypeIdentifier.ofType(type);

        if (!analyzedTypes.contains(type) && (type.isAssignableTo(COLLECTION) || !isJDKType(type))) {
            analyzedTypes.add(type);
            typeRepresentations.put(identifier, analyzeInternal(identifier, type));
        }

        return identifier;
    }

    private static boolean isJDKType(final Type type) {
        // exclude java, javax, etc. packages
        if (Types.PRIMITIVE_TYPES.contains(type))
            return true;

        final String name = type.toString();
        return name.startsWith("java.") || name.startsWith("javax.");
    }

    private TypeRepresentation analyzeInternal(final TypeIdentifier identifier, final Type type) {
        if (type.isAssignableTo(COLLECTION)) {
            final Type containedType = ResponseTypeNormalizer.normalizeCollection(type);
            return TypeRepresentation.ofCollection(identifier, analyzeInternal(TypeIdentifier.ofType(containedType), containedType));
        }

        return TypeRepresentation.ofConcrete(identifier, analyzeClass(type));
    }

    private Map<String, TypeIdentifier> analyzeClass(final Type type) {
        final CtClass ctClass = type.getCtClass();
        if (ctClass.isEnum() || isJDKType(type))
            return Collections.emptyMap();

        final XmlAccessType value = getXmlAccessType(ctClass);

        final List<CtField> relevantFields = Stream.of(ctClass.getDeclaredFields()).collect(Collectors.toList());
        final List<CtMethod> relevantGetters = Stream.of(ctClass.getDeclaredMethods()).filter(m -> isRelevant(m, value)).collect(Collectors.toList());

        final Map<String, TypeIdentifier> properties = new HashMap<>();

        // calculate inherited properties in inheritance chain
        try {
            // get interfaces
            Arrays.stream(ctClass.getInterfaces())
                .map(interfaceType -> this.analyzeClass(new Type(interfaceType.getName())))
                .forEach(interfaceProperties -> {
                    properties.putAll(interfaceProperties);
                });

            // get superclass
            final Type superType = new Type(ctClass.getSuperclass().getName());
            final Map<String, TypeIdentifier> superProperties = this.analyzeClass(superType);

            properties.putAll(superProperties);

            // get class properties
            relevantFields.stream().map(f -> mapField(f, type))
                .filter(Objects::nonNull).forEach(p -> {
                properties.put(p.getLeft(), TypeIdentifier.ofType(p.getRight().getLeft(), p.getRight().getRight()));
                analyze(p.getRight().getLeft());
            });
        } catch (Exception e) {
            // TODO: more descriptive error
            // - this will occur if the superclass is not found in classpath
            throw new RuntimeException(e);
        }

        return properties;
    }

    private XmlAccessType getXmlAccessType(CtClass ctClass) {
        try {
            CtClass current = ctClass;

            while (current != null) {
                if (current.hasAnnotation(XmlAccessorType.class))
                    return ((XmlAccessorType) current.getAnnotation(XmlAccessorType.class)).value();
                current = current.getSuperclass();
            }

        } catch (ClassNotFoundException | NotFoundException e) {
            LogProvider.error("Could not analyze JAXB annotation of type: " + e.getMessage());
            LogProvider.debug(e);
        }

        return XmlAccessType.PUBLIC_MEMBER;
    }

    private static boolean isRelevant(final CtField field, final XmlAccessType accessType) {
        if (JavaUtils.isSynthetic(field))
            return false;

        if (field.hasAnnotation(XmlElement.class))
            return true;

        final int modifiers = field.getModifiers();
        if (accessType == XmlAccessType.FIELD)
            // always take, unless static or transient
            return !Modifier.isTransient(modifiers) && !Modifier.isStatic(modifiers) && !field.hasAnnotation(XmlTransient.class);
        else if (accessType == XmlAccessType.PUBLIC_MEMBER)
            // only for public, non-static
            return Modifier.isPublic(modifiers) && !Modifier.isStatic(modifiers) && !field.hasAnnotation(XmlTransient.class);

        return false;
    }

    /**
     * Checks if the method is public and non-static and that the method is a Getter. Does not allow methods with ignored names.
     * Does also not take methods annotated with {@link XmlTransient}
     *
     * @param method The method
     * @return {@code true} if the method should be analyzed further
     */
    private static boolean isRelevant(final CtMethod method, final XmlAccessType accessType) {
        if (JavaUtils.isSynthetic(method) || !isGetter(method))
            return false;

        if (method.hasAnnotation(XmlElement.class))
            return true;

        if (accessType == XmlAccessType.PROPERTY)
            return !method.hasAnnotation(XmlTransient.class);
        else if (accessType == XmlAccessType.PUBLIC_MEMBER)
            return Modifier.isPublic(method.getModifiers()) && !method.hasAnnotation(XmlTransient.class);

        return false;
    }

    private static boolean isGetter(final CtMethod method) {
        if (Modifier.isStatic(method.getModifiers()))
            return false;

        final String name = method.getName();
        if (Stream.of(NAMES_TO_IGNORE).anyMatch(n -> n.equals(name)))
            return false;

        if (name.startsWith("get") && name.length() > 3)
            return !method.getSignature().endsWith(")V");

        return name.startsWith("is") && name.length() > 2 && method.getSignature().endsWith(")Z");
    }

    private static Pair<String, Pair<Type, Boolean>> mapField(final CtField field, final Type containedType)
            {
        final Type type = JavaUtils.getFieldType(field, containedType);
        if (type == null)
            return null;
                Boolean hasAnnotations = false;
                try {
                    hasAnnotations = field.getAnnotations().length > 0;
                } catch (ClassNotFoundException e) {
                    hasAnnotations = true;
                }

                return Pair.of(field.getName(), Pair.of(type, hasAnnotations));
    }

    private static Pair<String, Pair<Type, Boolean>> mapGetter(final CtMethod method, final Type containedType) {
        Boolean isMandatory = null;
        try {
            isMandatory = method.getAnnotations().length > 0;
        } catch (ClassNotFoundException e) {
            e.printStackTrace();
        }
        final Type returnType = JavaUtils.getReturnType(method, containedType);
        if (returnType == null)
            return null;

        return Pair.of(normalizeGetter(method.getName()), Pair.of(returnType, isMandatory));
    }

    /**
     * Converts a getter name to the property name (without the "get" or "is" and lowercase).
     *
     * @param name The name of the method (MUST match "get[A-Z][A-Za-z]*|is[A-Z][A-Za-z]*")
     * @return The name of the property
     */
    private static String normalizeGetter(final String name) {
        final int size = name.startsWith("is") ? 2 : 3;
        final char chars[] = name.substring(size).toCharArray();
        chars[0] = Character.toLowerCase(chars[0]);
        return new String(chars);
    }

}
