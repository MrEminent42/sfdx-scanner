package com.salesforce.graph.ops;

import static com.salesforce.apex.jorje.ASTConstants.PROPERTY_METHOD_PREFIX;
import static com.salesforce.graph.ops.TypeableUtil.NOT_A_MATCH;
import static org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__.out;

import com.salesforce.apex.jorje.ASTConstants;
import com.salesforce.apex.jorje.ASTConstants.NodeType;
import com.salesforce.collections.CollectionUtil;
import com.salesforce.exception.UnexpectedException;
import com.salesforce.graph.ApexPath;
import com.salesforce.graph.Schema;
import com.salesforce.graph.build.CaseSafePropertyUtil.H;
import com.salesforce.graph.ops.expander.ApexPathExpanderConfig;
import com.salesforce.graph.symbols.*;
import com.salesforce.graph.symbols.apex.ApexClassInstanceValue;
import com.salesforce.graph.symbols.apex.ApexForLoopValue;
import com.salesforce.graph.symbols.apex.ApexStandardValue;
import com.salesforce.graph.symbols.apex.ApexValue;
import com.salesforce.graph.symbols.apex.system.SystemSchema;
import com.salesforce.graph.vertex.*;
import com.salesforce.graph.visitor.ApexPathWalker;
import com.salesforce.graph.visitor.DefaultNoOpPathVertexVisitor;
import com.salesforce.messaging.CliMessager;
import com.salesforce.messaging.EventKey;
import com.salesforce.rules.AbstractRuleRunner.RuleRunnerTarget;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.tinkerpop.gremlin.process.traversal.P;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;
import org.apache.tinkerpop.gremlin.structure.Vertex;

@SuppressWarnings("PMD") // Heavily used and risky to fix at the moment
public final class MethodUtil {
    private static final Logger LOGGER = LogManager.getLogger(MethodUtil.class);

    public static List<MethodVertex> getTargetedMethods(
            GraphTraversalSource g, List<RuleRunnerTarget> targets) {
        // The targets passed into this method should exclusively be ones that target specific
        // methods instead of files.
        if (targets.stream().anyMatch(t -> t.getTargetMethods().size() == 0)) {
            throw new UnexpectedException(
                    "MethodUtil.getTargetedMethods() should only be called with method-level targets.");
        }

        List<MethodVertex> methodVertices = new ArrayList<>();

        // By running one query per target and recombining the results, we make the query much
        // simpler than if we tried
        // to do them all at once. It's probably slightly less performant, but in the scheme of
        // things, the performance
        // hit is negligible.
        for (RuleRunnerTarget target : targets) {
            List<MethodVertex> targetMethodVertices =
                    SFVertexFactory.loadVertices(
                            g,
                            g.V()
                                    .where(
                                            H.hasWithin(
                                                    NodeType.METHOD,
                                                    Schema.NAME,
                                                    target.getTargetMethods()))
                                    .where(
                                            __.repeat(__.out(Schema.PARENT))
                                                    .until(__.has(Schema.FILE_NAME))
                                                    .has(Schema.FILE_NAME, target.getTargetFile())
                                                    .count()
                                                    .is(P.eq(1))));
            addMessagesForTarget(target, targetMethodVertices);
            methodVertices.addAll(targetMethodVertices);
        }
        return methodVertices;
    }

    /**
     * If any of the method names specified by the provided target returned multiple results or zero
     * results, adds a message to a {@link CliMessager} instance indicating such.
     *
     * @param target - A target that specifies a file and methods within that file
     * @param vertices - The method vertices returned by the query created using the target
     */
    private static void addMessagesForTarget(RuleRunnerTarget target, List<MethodVertex> vertices) {
        TreeMap<String, Integer> methodCountByName = CollectionUtil.newTreeMap();
        // Map each vertex's method name to the number of vertices sharing that name.
        for (MethodVertex methodVertex : vertices) {
            String methodName = methodVertex.getName();
            int priorCount = methodCountByName.getOrDefault(methodName, 0);
            methodCountByName.put(methodName, priorCount + 1);
        }
        // For each of the methods we were instructed to target, see how many methods with that name
        // were found.
        for (String targetMethod : target.getTargetMethods()) {
            Integer methodCount = methodCountByName.getOrDefault(targetMethod, 0);
            if (methodCount == 0) {
                CliMessager.getInstance()
                        .addMessage(
                                "Loading " + targetMethod + " vertices",
                                EventKey.WARNING_NO_METHOD_TARGET_MATCHES,
                                target.getTargetFile(),
                                targetMethod);
            } else if (methodCount > 1) {
                CliMessager.getInstance()
                        .addMessage(
                                "Loading " + targetMethod + " vertices",
                                EventKey.WARNING_MULTIPLE_METHOD_TARGET_MATCHES,
                                methodCount.toString(),
                                target.getTargetFile(),
                                targetMethod);
            }
        }
    }

    public static Optional<MethodVertex> getInvoked(
            GraphTraversalSource g,
            final String definingType,
            MethodCallExpressionVertex vertex,
            SymbolProvider symbols) {
        // Iterate up the hierarchy until a method is found. This purposefully iterates one class at
        // a time instead of
        // querying up the graph inheritance chain and gathering all methods. Querying the edge
        // chain would require us
        // to collapse the methods to avoid collisions that don't exist. This can be revisited if it
        // is too inefficient.
        // Note: this does not exclude private subclass methods and assumes validity of access, we
        // aren't a compiler
        final HierarchyIterator iterator = new HierarchyIterator(definingType);
        List<MethodVertex> methods = Collections.emptyList();

        String foundDefiningType = definingType;
        while (foundDefiningType != null && methods.isEmpty()) {
            methods =
                    SFVertexFactory.loadVertices(
                            g,
                            g.V()
                                    .where(
                                            H.has(
                                                    NodeType.METHOD,
                                                    Schema.DEFINING_TYPE,
                                                    foundDefiningType))
                                    .where(
                                            H.has(
                                                    NodeType.METHOD,
                                                    Schema.NAME,
                                                    vertex.getMethodName()))
                                    .has(Schema.ARITY, vertex.getParameters().size()));
            if (methods.isEmpty()) {
                foundDefiningType = iterator.getNext(g).orElse(null);
            } else {
                Optional<MethodVertex> invoked = getInvoked(methods, vertex, symbols);
                if (invoked.isPresent()) {
                    return invoked;
                }
            }
        }

        final String fullType =
                ClassUtil.getMoreSpecificClassName(vertex, definingType).orElse(null);
        if (fullType != null) {
            // Matches an outer class calling an inner class
            return getInvoked(g, fullType, vertex, symbols);
        }

        return Optional.empty();
    }

    /** Finds the method that corresponds to an Apex Property */
    public static Optional<MethodVertex> getPropertyMethodInvoked(
            GraphTraversalSource g, String definingType, String propertyName, boolean isGetter) {
        HierarchyIterator iterator = new HierarchyIterator(definingType);
        List<MethodVertex> methods = Collections.emptyList();

        while (definingType != null && methods.isEmpty()) {
            methods =
                    SFVertexFactory.loadVertices(
                            g,
                            g.V()
                                    .where(
                                            H.has(
                                                    NodeType.METHOD,
                                                    Schema.DEFINING_TYPE,
                                                    definingType))
                                    .where(
                                            H.has(
                                                    NodeType.METHOD,
                                                    Schema.NAME,
                                                    PROPERTY_METHOD_PREFIX + propertyName))
                                    // Only include properties that have block statements. Exclude
                                    // things like get; and set;
                                    .where(out(Schema.CHILD).hasLabel(NodeType.BLOCK_STATEMENT))
                                    .has(Schema.ARITY, isGetter ? 0 : 1));
            if (methods.isEmpty()) {
                definingType = iterator.getNext(g).orElse(null);
            } else if (methods.size() == 1) {
                return Optional.of(methods.get(0));
            } else {
                throw new UnexpectedException(definingType + "#" + propertyName);
            }
        }

        return Optional.empty();
    }

    public static Optional<MethodVertex> getInvoked(
            GraphTraversalSource g, NewObjectExpressionVertex vertex, SymbolProvider symbols) {
        UserClassVertex userClass = ClassUtil.getUserClass(g, vertex).orElse(null);
        if (userClass != null) {
            List<MethodVertex> methods =
                    SFVertexFactory.loadVertices(
                            g,
                            g.V()
                                    .where(
                                            H.has(
                                                    NodeType.METHOD,
                                                    Schema.DEFINING_TYPE,
                                                    userClass.getDefiningType()))
                                    .where(
                                            H.has(
                                                    NodeType.METHOD,
                                                    Schema.NAME,
                                                    Schema.INSTANCE_CONSTRUCTOR_CANONICAL_NAME))
                                    .has(Schema.CONSTRUCTOR, true)
                                    .has(Schema.ARITY, vertex.getParameters().size()));

            return getInvoked(methods, vertex, symbols);
        } else {
            return Optional.empty();
        }
    }

    /**
     * @return constructor that takes no arguments for {@code className} if it exists. This can be a
     *     user defined or default constructor provided by the compiler.
     */
    public static Optional<MethodVertex.ConstructorVertex> getNoArgConstructor(
            GraphTraversalSource g, String className) {
        return Optional.ofNullable(
                SFVertexFactory.loadSingleOrNull(
                        g,
                        g.V()
                                .where(H.has(NodeType.METHOD, Schema.DEFINING_TYPE, className))
                                .where(
                                        H.has(
                                                NodeType.METHOD,
                                                Schema.NAME,
                                                Schema.INSTANCE_CONSTRUCTOR_CANONICAL_NAME))
                                .has(Schema.CONSTRUCTOR, true)
                                .has(Schema.ARITY, 0)));
    }

    /**
     * @return constructors explicitly declared in code.
     */
    public static List<MethodVertex.ConstructorVertex> getNonDefaultConstructors(
            GraphTraversalSource g, String className) {
        return SFVertexFactory.loadVertices(
                g,
                g.V()
                        .where(H.has(NodeType.METHOD, Schema.DEFINING_TYPE, className))
                        .where(
                                H.has(
                                        NodeType.METHOD,
                                        Schema.NAME,
                                        Schema.INSTANCE_CONSTRUCTOR_CANONICAL_NAME))
                        .has(Schema.CONSTRUCTOR, true)
                        // User defined constructors have a block statement
                        .where(out(Schema.CHILD).hasLabel(NodeType.BLOCK_STATEMENT)));
    }

    public static Optional<MethodVertex> getInvoked(
            GraphTraversalSource g,
            SuperMethodCallExpressionVertex vertex,
            SymbolProvider symbols) {
        UserClassVertex userClass =
                ClassUtil.getUserClass(g, vertex.getDefiningType()).orElse(null);
        String superClassName = userClass.getSuperClassName().orElse(null);

        HierarchyIterator iterator = new HierarchyIterator(superClassName);
        List<MethodVertex> methods = Collections.emptyList();

        while (superClassName != null && methods.isEmpty()) {
            methods =
                    SFVertexFactory.loadVertices(
                            g,
                            g.V()
                                    .where(
                                            H.has(
                                                    NodeType.METHOD,
                                                    Schema.DEFINING_TYPE,
                                                    superClassName))
                                    .where(
                                            H.has(
                                                    NodeType.METHOD,
                                                    Schema.NAME,
                                                    Schema.INSTANCE_CONSTRUCTOR_CANONICAL_NAME))
                                    .has(Schema.CONSTRUCTOR, true)
                                    .has(Schema.ARITY, vertex.getParameters().size()));
            if (methods.isEmpty()) {
                superClassName = iterator.getNext(g).orElse(null);
            } else {
                Optional<MethodVertex> invoked = getInvoked(methods, vertex, symbols);
                if (invoked.isPresent()) {
                    return invoked;
                }
            }
        }

        return Optional.empty();
    }

    public static Optional<MethodVertex> getInvoked(
            GraphTraversalSource g, ThisMethodCallExpressionVertex vertex, SymbolProvider symbols) {
        String className = vertex.getDefiningType();
        List<MethodVertex> methods =
                SFVertexFactory.loadVertices(
                        g,
                        g.V()
                                .where(H.has(NodeType.METHOD, Schema.DEFINING_TYPE, className))
                                .where(
                                        H.has(
                                                NodeType.METHOD,
                                                Schema.NAME,
                                                Schema.INSTANCE_CONSTRUCTOR_CANONICAL_NAME))
                                .has(Schema.CONSTRUCTOR, true)
                                .has(Schema.ARITY, vertex.getParameters().size()));

        return getInvoked(methods, vertex, symbols);
    }

    public static Optional<MethodVertex> getInvoked(
            List<MethodVertex> methods,
            InvocableWithParametersVertex invocable,
            SymbolProvider symbols) {
        if (methods.isEmpty()) {
            return Optional.empty();
        } else {
            final TreeMap<Integer, MethodVertex> matchedVertices = new TreeMap<>();
            for (MethodVertex method : methods) {
                if (method.getParameters().size() != invocable.getParameters().size()) {
                    if (LOGGER.isWarnEnabled()) {
                        LOGGER.warn(
                                "getInvoked was passed mismatching parameters. methods="
                                        + methods
                                        + ", invocable="
                                        + invocable);
                    }
                    continue;
                }
                final int matchRank =
                        MethodTypeMatchUtil.parameterTypesMatch(method, invocable, symbols);
                if (matchRank != NOT_A_MATCH) {
                    // Make sure we have only one method at a given rank.
                    // Multiple matches at the same rank would be a compilation error.
                    if (matchedVertices.containsKey(matchRank)) {
                        throw new UnexpectedException(
                                "Multiple methods should not resolve to the same rank. Existing match: "
                                        + matchedVertices.get(matchRank)
                                        + ", new match: "
                                        + method);
                    }
                    matchedVertices.put(matchRank, method);
                }
            }

            if (matchedVertices.isEmpty()) {
                return Optional.empty();
            }

            return Optional.of(matchedVertices.get(matchedVertices.firstKey()));
        }
    }

    /**
     * Used when starting traversal from within a method and we aren't sure what the method was
     * invoked with. Returns a {@link MethodInvocationScope} with all values set to an indeterminant
     * value of the correct type.
     */
    public static MethodInvocationScope getIndeterminantMethodInvocationScope(MethodVertex method) {
        TreeMap<String, Pair<Typeable, ApexValue<?>>> apexValues = CollectionUtil.newTreeMap();
        for (ParameterVertex parameter : method.getParameters()) {
            ApexValue<?> apexValue = ApexValueUtil.synthesizeMethodParameter(parameter);
            apexValues.put(parameter.getName(), Pair.of(parameter, apexValue));
        }
        return new MethodInvocationScope(null, apexValues);
    }

    /**
     * Given a vertex representing a method declaration, returns all of the vertices representing
     * potential invocations of that method
     *
     * @param g - A graph traversal source
     * @param methodVertex - A vertex defining a method
     * @return All MethodCallExpressionVertex nodes that could potentially invoke this method
     */
    public static List<MethodCallExpressionVertex> getPotentialCallers(
            GraphTraversalSource g, MethodVertex methodVertex) {
        // No matter what we're looking for, we'll need the definingType and canonicalName values to
        // find it.
        String definingType = methodVertex.getDefiningType();
        String canonicalName = methodVertex.getName();

        GraphTraversal<Vertex, Vertex> gt;

        // How we build our GREMLIN query depends on whether the method is static or not.
        if (methodVertex.getModifierNode().isStatic()) {
            // For static methods, we want all of the method call expressions where...
            gt =
                    g.V()
                            .hasLabel(NodeType.METHOD_CALL_EXPRESSION)
                            // One of the following is true:
                            .or(
                                    // The FullMethodName is [this class].[this method]`, meaning
                                    // it's explicitly calling the desired method.
                                    H.has(
                                            NodeType.METHOD_CALL_EXPRESSION,
                                            Schema.FULL_METHOD_NAME,
                                            definingType + "." + canonicalName),
                                    // The DefiningType is the same and the FullMethodName equals
                                    // the canonical name, meaning it's
                                    // an implicit call from within the same class.
                                    H.has(
                                                    NodeType.METHOD_CALL_EXPRESSION,
                                                    Schema.DEFINING_TYPE,
                                                    definingType)
                                            .where(
                                                    H.has(
                                                            NodeType.METHOD_CALL_EXPRESSION,
                                                            Schema.FULL_METHOD_NAME,
                                                            canonicalName)));
        } else {
            // For instance methods, we want all of the method call expressions where...
            gt =
                    g.V()
                            .hasLabel(NodeType.METHOD_CALL_EXPRESSION)
                            // One of the following is true:
                            .or(
                                    // The DefiningType is the same and the FullMethodName equals
                                    // the canonical name, meaning it's
                                    // an implicit call from within the same class.
                                    H.has(
                                            NodeType.METHOD_CALL_EXPRESSION,
                                            Schema.FULL_METHOD_NAME,
                                            canonicalName),
                                    // The MethodName property is the same as the canonical name.
                                    // This is true when an instance method
                                    // is called on an object. IMPORTANT: Nodes returned for this
                                    // subquery are not guaranteed to
                                    // be invocations, because of shenanigans with inheritance and
                                    // typing.
                                    H.has(
                                            NodeType.METHOD_CALL_EXPRESSION,
                                            Schema.METHOD_NAME,
                                            canonicalName));
        }

        // We also want to filter by arity.
        // TODO: How does this filter by arity?
        gt =
                gt.asAdmin()
                        .clone()
                        .where(
                                __.out(Schema.CHILD)
                                        .label()
                                        .is(
                                                P.without(
                                                        NodeType.EMPTY_REFERENCE_EXPRESSION,
                                                        NodeType.REFERENCE_EXPRESSION))
                                        .count()
                                        .is(P.eq(methodVertex.getArity())));

        // Run our query.
        List<MethodCallExpressionVertex> potentialCallers =
                SFVertexFactory.loadVertices(g, gt.asAdmin().clone());
        // Then filter the results to remove entries where parameter types don't align.
        potentialCallers =
                potentialCallers.stream()
                        .filter(v -> invocableIsPotentialCaller(g, methodVertex, v))
                        .collect(Collectors.toList());

        return potentialCallers;
    }

    private static boolean invocableIsPotentialCaller(
            GraphTraversalSource g, MethodVertex method, MethodCallExpressionVertex mcev) {
        // We need one of the paths flowing into this method call. We don't need to do a length
        // check because at least
        // one path is guaranteed to exist.
        ApexPathExpanderConfig expanderConfig = ApexPathExpanderConfig.Builder.get().build();
        ApexPath reversePath = ApexPathUtil.getReversePaths(g, mcev, expanderConfig).get(0);
        // Create a PathVertexVisitor that will derive the information we're looking for.
        MethodFinderVisitor mfv = new MethodFinderVisitor(g, mcev, method);
        // Create a symbol provider.
        DefaultSymbolProviderVertexVisitor pspvv = new DefaultSymbolProviderVertexVisitor(g);
        // Walk the path...
        ApexPathWalker.walkPath(g, reversePath, mfv, pspvv);

        // Once the path has been walked, we can check whether it determined that the invocation
        // calls the right method.
        return mfv.isPathSatisfactory();
    }

    public static Optional<ApexValue<?>> getApexValue(
            ChainedVertex vertex, SymbolProvider symbols) {
        String symbolicName = vertex.getSymbolicName().orElse(null);
        ApexValue<?> apexValue = null;
        if (symbolicName != null) {
            // TODO: Make general case
            if (ApexStandardLibraryUtil.getCanonicalName(symbolicName)
                    .equalsIgnoreCase(ApexStandardLibraryUtil.Type.SYSTEM_SCHEMA)) {
                apexValue = SystemSchema.getInstance();
            } else {
                // Symbolic method call such as c.myMethod()

                // if this is a variable, resolve to the class instance's variable if it contains a
                // "this." e.g. this.varName
                if ((vertex instanceof VariableExpressionVertex)
                        && ((VariableExpressionVertex) vertex).isThisReference()) {
                    apexValue = symbols.getApexValueFromInstanceScope(symbolicName).orElse(null);
                } else {
                    apexValue = symbols.getApexValue(symbolicName).orElse(null);
                }
            }
        }

        if (apexValue == null && vertex instanceof InvocableVertex) {
            // Chained method call such as MySingleton.getInstance().getName()
            apexValue = symbols.getReturnedValue((InvocableVertex) vertex).orElse(null);
        }

        if (apexValue == null && vertex instanceof MethodCallExpressionVertex) {
            final Optional<ArrayLoadExpressionVertex> arrayExpressionVertex =
                    ((MethodCallExpressionVertex) vertex).getArrayInvocation();
            if (arrayExpressionVertex.isPresent()) {
                apexValue =
                        ScopeUtil.resolveToApexValue(symbols, arrayExpressionVertex.get())
                                .orElse(null);
            }
        }

        return Optional.ofNullable(apexValue);
    }

    public static List<ApexPath> getPaths(
            GraphTraversalSource g, ChainedVertex vertex, SymbolProvider symbols) {
        MethodVertex invoked = null;

        // Try to find the method on an instance variable first
        ApexValue<?> apexValue = getApexValue(vertex, symbols).orElse(null);

        if (vertex instanceof VariableExpressionVertex.Single) {
            // This might be an complex Apex Property that has a Method block such as
            // String aString { get { return 'Hello'; } }
            VariableExpressionVertex.Single variableExpression =
                    (VariableExpressionVertex.Single) vertex;
            String methodName = variableExpression.getName();
            AbstractClassScope abstractClassScope;
            if (apexValue instanceof ApexClassInstanceValue) {
                ApexClassInstanceValue apexClassInstance = (ApexClassInstanceValue) apexValue;
                abstractClassScope = apexClassInstance.getClassInstanceScope();
            } else {
                abstractClassScope = symbols.getClosestClassInstanceScope().orElse(null);
                if (abstractClassScope == null) {
                    abstractClassScope =
                            ContextProviders.CLASS_STATIC_SCOPE
                                    .get()
                                    .getClassStaticScope(vertex.getDefiningType())
                                    .orElse(null);
                }
            }
            if (abstractClassScope != null) {
                AbstractReferenceExpressionVertex abstractReferenceExpression =
                        variableExpression.getReferenceExpression();
                String referenceType;
                if (abstractReferenceExpression instanceof EmptyReferenceExpressionVertex) {
                    // References to synthetic properties without a "this" qualifier have a
                    // EmptyReferenceExpressionVertex. This
                    // vertex doesn't contain information about whether it is a store ore a load. We
                    // infer this by looking at the
                    // parent. We assume it's a STORE If the parent is an AssignmentExpressionVertex
                    // and this vertex is
                    // the first child.
                    if (vertex.getParent() instanceof AssignmentExpressionVertex
                            && vertex.getChildIndex().equals(0)) {
                        referenceType = ASTConstants.ReferenceType.STORE;
                    } else {
                        referenceType = ASTConstants.ReferenceType.LOAD;
                    }
                } else if (abstractReferenceExpression instanceof ReferenceExpressionVertex) {
                    ReferenceExpressionVertex referenceExpression =
                            (ReferenceExpressionVertex) variableExpression.getReferenceExpression();
                    referenceType = referenceExpression.getReferenceType();
                } else {
                    throw new UnexpectedException(vertex);
                }
                // Getters/Setters are identified by their ReferenceType
                if (referenceType.equals(ASTConstants.ReferenceType.LOAD)
                        || referenceType.equals(ASTConstants.ReferenceType.STORE)) {
                    String definingType = abstractClassScope.getClassName();
                    invoked =
                            getPropertyMethodInvoked(
                                            g,
                                            definingType,
                                            methodName,
                                            referenceType.equals(ASTConstants.ReferenceType.LOAD))
                                    .orElse(null);
                }
            }
        } else if (apexValue != null && apexValue.getDefiningType().isPresent()) {
            String definingType = apexValue.getDefiningType().get();
            if (vertex instanceof MethodCallExpressionVertex
                    && (apexValue instanceof ApexClassInstanceValue
                            || apexValue instanceof ApexStandardValue
                            || apexValue instanceof ApexForLoopValue)) {
                // Only resolve methods on ApexClassInstanceValue and ApexStandardValue. This avoids
                // attempting to
                // call instance methods using a static class scope
                invoked =
                        getInvoked(g, definingType, (MethodCallExpressionVertex) vertex, symbols)
                                .orElse(null);
            } else if (vertex instanceof ArrayLoadExpressionVertex) {
                // Intentionally left blank
            } else {
                if (LOGGER.isTraceEnabled()) {
                    LOGGER.trace(
                            "Ignoring method that was called on a type which isn't available in source. vertex="
                                    + vertex);
                }
            }
        } else if (vertex instanceof MethodCallExpressionVertex) {
            MethodCallExpressionVertex methodCallExpression = (MethodCallExpressionVertex) vertex;
            MethodCallExpressionVertex firstInChain = methodCallExpression.getFirst();

            if (methodCallExpression.equals(
                    firstInChain)) { // If methodCallExpression and firstInChain differ then we are
                // calling a method on a type that can't be resolved
                // The vertex is not tied to an instance, try to find an implementing method on the
                // current class or a static
                // method on a another class
                String definingType;
                final String methodName = methodCallExpression.getMethodName();
                String fullMethodName = methodCallExpression.getFullMethodName();
                if (methodName.equals(fullMethodName)) {
                    // The method is being called on a class onto itself
                    definingType = vertex.getDefiningType();
                } else {
                    // TODO: Pass information to #getInvoked that this needs to be a static method
                    definingType = String.join(".", vertex.getChainedNames());
                }
                // The defining type could be an aliased reference to an inner class, so check that
                // first.
                String potentialInnerClassDefType =
                        ClassUtil.getMoreSpecificClassName(vertex, definingType).orElse(null);
                if (potentialInnerClassDefType != null) {
                    invoked =
                            getInvoked(
                                            g,
                                            potentialInnerClassDefType,
                                            (MethodCallExpressionVertex) vertex,
                                            symbols)
                                    .orElse(null);
                }
                // If we found no inner classes, check outer classes.
                if (invoked == null) {
                    definingType = ApexStandardLibraryUtil.getCanonicalName(definingType);
                    invoked =
                            getInvoked(
                                            g,
                                            definingType,
                                            (MethodCallExpressionVertex) vertex,
                                            symbols)
                                    .orElse(null);
                }
            }
        } else if (vertex instanceof NewObjectExpressionVertex) {
            invoked = getInvoked(g, (NewObjectExpressionVertex) vertex, symbols).orElse(null);
        } else if (vertex instanceof SuperMethodCallExpressionVertex) {
            invoked = getInvoked(g, (SuperMethodCallExpressionVertex) vertex, symbols).orElse(null);
        } else if (vertex instanceof ThisMethodCallExpressionVertex) {
            invoked = getInvoked(g, (ThisMethodCallExpressionVertex) vertex, symbols).orElse(null);
        } else if (vertex instanceof ArrayLoadExpressionVertex) {
            // Intentionally left blank. This will be handled without a path.
        } else if (vertex instanceof NewCollectionExpressionVertex) {
            // Intentionally left blank. This will be handled without a path.
        } else if (vertex instanceof SoqlExpressionVertex) {
            // Intentionally left blank. This will be handled without a path.
        } else {
            throw new UnexpectedException(vertex);
        }

        if (invoked != null) {
            if (apexValue instanceof ApexForLoopValue && apexValue.getDefiningType().isPresent()) {
                // TODO: We have a situation where we need to indicate that the return value should
                // be a forloop value too
            }
            if (LOGGER.isTraceEnabled()) {
                LOGGER.trace("Finding forward path. vertex=" + vertex + ", invoked=" + invoked);
            }
            List<ApexPath> paths = ApexPathUtil.getForwardPaths(g, invoked, false);
            return paths;
        } else {
            return Collections.emptyList();
        }
    }

    private MethodUtil() {}

    static class MethodFinderVisitor extends DefaultNoOpPathVertexVisitor {
        private boolean pathIsSatisfactory = false;
        private final GraphTraversalSource g;
        private final MethodCallExpressionVertex mcev;
        private final MethodVertex method;
        private final String methodVariableName;

        MethodFinderVisitor(
                GraphTraversalSource g, MethodCallExpressionVertex mcev, MethodVertex method) {
            this.g = g;
            this.mcev = mcev;
            this.method = method;
            this.methodVariableName =
                    !method.getModifierNode().isStatic()
                            ? mcev.getSymbolicName().orElse(null)
                            : null;
        }

        boolean isPathSatisfactory() {
            return pathIsSatisfactory;
        }

        @Override
        public boolean visit(MethodCallExpressionVertex vertex, SymbolProvider symbols) {
            if (!mcev.equals(vertex)) {
                // For any vertex other than the one representing the method call, we can return
                // early.
                return true;
            } else if (methodVariableName != null
                    && !MethodTypeMatchUtil.methodVariableTypeMatches(
                            g, method, methodVariableName, symbols)) {
                // If the invocation is for an instance method of the wrong type, we can return
                // early.
                return true;
            } else if (MethodTypeMatchUtil.parameterTypesMatch(method, mcev, symbols)
                    == NOT_A_MATCH) {
                // If the method's parameter types don't match the arguments of the method call,
                // return early.
                return true;
            }
            // If we're here, then all of our criteria were met. We should change the boolean
            // property to reflect this,
            // and then return.
            this.pathIsSatisfactory = true;
            return true;
        }
    }

    /**
     * TODO: Efficiency, query the edges and accumulate the superclasses, support interfaces This
     * allows us to traverse up the hierarchy one class at at time until we find a method that
     * matches a particular signature.
     */
    private static final class HierarchyIterator {
        private String type;

        private HierarchyIterator(String type) {
            this.type = type;
        }

        private Optional<String> getNext(GraphTraversalSource g) {
            if (type == null) {
                throw new UnexpectedException("Reached end");
            }
            UserClassVertex currentClass = ClassUtil.getUserClass(g, type).orElse(null);
            String superClassName = null;
            if (currentClass != null) {
                superClassName = currentClass.getSuperClassName().orElse(null);
            }
            type = superClassName;
            return Optional.ofNullable(type);
        }
    }
}
