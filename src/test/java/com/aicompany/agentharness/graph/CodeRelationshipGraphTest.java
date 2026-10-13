package com.aicompany.agentharness.graph;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CodeRelationshipGraphTest {

	@TempDir
	Path sourceRoot;

	private final CodeRelationshipGraphBuilder builder = new CodeRelationshipGraphBuilder();

	@Test
	void extractsFieldDependency() throws IOException {
		write("OrderRepository.java", "interface OrderRepository {}");
		write("OrderService.java", """
				class OrderService {
				    private OrderRepository repository;
				}
				""");

		CodeRelationship relationship = onlyRelationship(builder.build(sourceRoot));

		assertThat(relationship.type()).isEqualTo(RelationshipType.DEPENDS_ON);
		assertThat(relationship.source().simpleName()).isEqualTo("OrderService");
		assertThat(relationship.target().simpleName()).isEqualTo("OrderRepository");
		assertThat(relationship.sourceKinds()).containsExactly(RelationshipSource.FIELD);
	}

	@Test
	void extractsConstructorDependency() throws IOException {
		write("OrderRepository.java", "interface OrderRepository {}");
		write("OrderService.java", """
				class OrderService {
				    OrderService(OrderRepository repository) {
				    }
				}
				""");

		CodeRelationship relationship = onlyRelationship(builder.build(sourceRoot));

		assertThat(relationship.type()).isEqualTo(RelationshipType.DEPENDS_ON);
		assertThat(relationship.sourceKinds()).containsExactly(RelationshipSource.CONSTRUCTOR_PARAMETER);
	}

	@Test
	void mergesFieldAndConstructorIntoOneRelationship() throws IOException {
		write("OrderRepository.java", "interface OrderRepository {}");
		write("OrderService.java", """
				class OrderService {
				    private OrderRepository repository;

				    OrderService(OrderRepository repository) {
				        this.repository = repository;
				    }
				}
				""");

		CodeRelationshipGraph graph = builder.build(sourceRoot);

		assertThat(graph.getRelationships()).hasSize(1);
		assertThat(graph.getRelationships().get(0).sourceKinds()).containsExactly(
				RelationshipSource.FIELD, RelationshipSource.CONSTRUCTOR_PARAMETER);
	}

	@Test
	void extractsMethodParameterDependency() throws IOException {
		write("OrderRequest.java", "class OrderRequest {}");
		write("OrderService.java", """
				class OrderService {
				    Order create(OrderRequest request) {
				        return null;
				    }
				}
				""");

		CodeRelationship relationship = onlyRelationship(builder.build(sourceRoot));

		assertThat(relationship.source().qualifiedName()).isEqualTo("OrderService");
		assertThat(relationship.target().qualifiedName()).isEqualTo("OrderRequest");
		assertThat(relationship.type()).isEqualTo(RelationshipType.DEPENDS_ON);
		assertThat(relationship.sourceKinds()).containsExactly(RelationshipSource.METHOD_PARAMETER);
	}

	@Test
	void extractsReturnTypeDependency() throws IOException {
		write("Order.java", "class Order {}");
		write("OrderService.java", """
				class OrderService {
				    Order create() {
				        return null;
				    }
				}
				""");

		CodeRelationship relationship = onlyRelationship(builder.build(sourceRoot));

		assertThat(relationship.target().qualifiedName()).isEqualTo("Order");
		assertThat(relationship.type()).isEqualTo(RelationshipType.DEPENDS_ON);
		assertThat(relationship.sourceKinds()).containsExactly(RelationshipSource.RETURN_TYPE);
	}

	@Test
	void extractsExtends() throws IOException {
		write("OrderService.java", "class OrderService {}");
		write("AdminOrderService.java", "class AdminOrderService extends OrderService {}");

		CodeRelationship relationship = onlyRelationship(builder.build(sourceRoot));

		assertThat(relationship.source().simpleName()).isEqualTo("AdminOrderService");
		assertThat(relationship.target().simpleName()).isEqualTo("OrderService");
		assertThat(relationship.type()).isEqualTo(RelationshipType.EXTENDS);
		assertThat(relationship.sourceKinds()).containsExactly(RelationshipSource.EXTENDS);
	}

	@Test
	void extractsImplements() throws IOException {
		write("OrderRepository.java", "interface OrderRepository {}");
		write("MySqlOrderRepository.java", "class MySqlOrderRepository implements OrderRepository {}");

		CodeRelationshipGraph graph = builder.build(sourceRoot);
		CodeRelationship relationship = onlyRelationship(graph);

		assertThat(graph.findNode("OrderRepository")).get().extracting(CodeNode::type).isEqualTo(CodeNodeType.INTERFACE);
		assertThat(relationship.source().simpleName()).isEqualTo("MySqlOrderRepository");
		assertThat(relationship.target().qualifiedName()).isEqualTo("OrderRepository");
		assertThat(relationship.type()).isEqualTo(RelationshipType.IMPLEMENTS);
	}

	@Test
	void extractsEveryImplementedInterface() throws IOException {
		write("A.java", "interface A {}");
		write("B.java", "interface B {}");
		write("Foo.java", "class Foo implements A, B {}");

		List<CodeRelationship> relationships = builder.build(sourceRoot).getRelationships();

		assertThat(relationships).hasSize(2);
		assertThat(relationships).allMatch(relationship -> relationship.type() == RelationshipType.IMPLEMENTS);
		assertThat(relationships).extracting(relationship -> relationship.target().simpleName()).containsExactly("A", "B");
	}

	@Test
	void ignoresExternalTypesAndKeepsProjectTypeInsideGeneric() throws IOException {
		write("Order.java", "class Order {}");
		write("OrderService.java", """
				import java.util.List;

				class OrderService {
				    private String name;
				    private List<Order> orders;
				}
				""");

		CodeRelationshipGraph graph = builder.build(sourceRoot);

		assertThat(graph.getNodes()).extracting(CodeNode::qualifiedName).containsExactly("Order", "OrderService");
		assertThat(graph.getRelationships()).singleElement().satisfies(relationship -> {
			assertThat(relationship.target().qualifiedName()).isEqualTo("Order");
			assertThat(relationship.type()).isEqualTo(RelationshipType.DEPENDS_ON);
			assertThat(relationship.sourceKinds()).containsExactly(RelationshipSource.FIELD);
		});
		assertThat(graph.findNode("java.lang.String")).isEmpty();
		assertThat(graph.findNode("java.util.List")).isEmpty();
	}

	@Test
	void resolvesImportedTypeInsteadOfMatchingSimpleName() throws IOException {
		write("com/example/shipping/OrderRepository.java", """
				package com.example.shipping;

				public interface OrderRepository {}
				""");
		write("com/example/billing/OrderRepository.java", """
				package com.example.billing;

				public interface OrderRepository {}
				""");
		write("com/example/billing/OrderService.java", """
				package com.example.billing;

				import com.example.shipping.OrderRepository;

				public class OrderService {
				    private OrderRepository repository;
				}
				""");

		CodeRelationshipGraph graph = builder.build(sourceRoot);

		assertThat(graph.getRelationships()).singleElement().satisfies(relationship -> {
			assertThat(relationship.source().qualifiedName()).isEqualTo("com.example.billing.OrderService");
			assertThat(relationship.target().qualifiedName()).isEqualTo("com.example.shipping.OrderRepository");
		});
		assertThat(graph.findRelationshipsTo("com.example.billing.OrderRepository")).isEmpty();
	}

	@Test
	void doesNotLinkSameSimpleNameInAnotherPackageWithoutImport() throws IOException {
		write("com/example/billing/OrderService.java", """
				package com.example.billing;

				public class OrderService {
				    private OrderRepository repository;
				}
				""");
		write("com/example/shipping/OrderRepository.java", """
				package com.example.shipping;

				public interface OrderRepository {}
				""");

		assertThat(builder.build(sourceRoot).getRelationships()).isEmpty();
	}

	@Test
	void rendersNodesAndRelationships() throws IOException {
		write("com/example/order/OrderRepository.java", """
				package com.example.order;

				public interface OrderRepository {}
				""");
		write("com/example/order/OrderService.java", """
				package com.example.order;

				public class OrderService {
				    private final OrderRepository repository;

				    public OrderService(OrderRepository repository) {
				        this.repository = repository;
				    }
				}
				""");
		write("com/example/order/OrderController.java", """
				package com.example.order;

				public class OrderController {
				    private final OrderService orderService;

				    public OrderController(OrderService orderService) {
				        this.orderService = orderService;
				    }
				}
				""");
		write("com/example/order/MySqlOrderRepository.java", """
				package com.example.order;

				public class MySqlOrderRepository implements OrderRepository {}
				""");

		String rendered = new CodeRelationshipGraphText().render(builder.build(sourceRoot));

		assertThat(rendered).isEqualTo("""
				Nodes:
				- CLASS com.example.order.MySqlOrderRepository
				- CLASS com.example.order.OrderController
				- INTERFACE com.example.order.OrderRepository
				- CLASS com.example.order.OrderService

				Relationships:
				- MySqlOrderRepository --IMPLEMENTS--> OrderRepository
				- OrderController --DEPENDS_ON--> OrderService [FIELD, CONSTRUCTOR_PARAMETER]
				- OrderService --DEPENDS_ON--> OrderRepository [FIELD, CONSTRUCTOR_PARAMETER]
				""");
	}

	@Test
	void keepsOneNodePerQualifiedName() throws IOException {
		write("com/example/order/OrderService.java", """
				package com.example.order;

				public class OrderService {}
				""");

		CodeRelationshipGraph graph = builder.build(sourceRoot);

		assertThat(graph.getNodes()).singleElement().satisfies(node -> {
			assertThat(node.type()).isEqualTo(CodeNodeType.CLASS);
			assertThat(node.simpleName()).isEqualTo("OrderService");
			assertThat(node.qualifiedName()).isEqualTo("com.example.order.OrderService");
			assertThat(node.filePath()).isEqualTo(sourceRoot.resolve("com/example/order/OrderService.java").toAbsolutePath().normalize());
		});
		assertThat(graph.findRelatedNodes("com.example.order.OrderService")).isEmpty();
	}

	private CodeRelationship onlyRelationship(CodeRelationshipGraph graph) {
		assertThat(graph.getRelationships()).hasSize(1);
		return graph.getRelationships().get(0);
	}

	private void write(String relativePath, String source) throws IOException {
		Path file = sourceRoot.resolve(relativePath);
		Files.createDirectories(file.getParent() == null ? sourceRoot : file.getParent());
		Files.writeString(file, source);
	}

}
