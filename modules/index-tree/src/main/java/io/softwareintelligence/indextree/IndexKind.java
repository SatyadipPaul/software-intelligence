package io.softwareintelligence.indextree;

/**
 * What a tree node stands for. Every kind except {@code ROOT} and {@code GROUP} corresponds to a
 * graph node the analyzer already proved; those two are the only synthetic shapes, and neither
 * asserts anything about the code — a root is where navigation starts, and a group exists only to
 * keep a card readable when a parent has too many children.
 */
public enum IndexKind {
    ROOT, CAPABILITY, MODULE, PACKAGE, TYPE, MEMBER, ENDPOINT, TOPIC, TABLE, GROUP
}
