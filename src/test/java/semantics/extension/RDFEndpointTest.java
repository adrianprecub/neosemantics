package semantics.extension;

import static org.junit.Assert.assertEquals;
import static org.neo4j.helpers.collection.Iterators.count;
import static org.neo4j.server.ServerTestUtils.getSharedTestTemporaryFolder;
import static semantics.RDFImport.PREFIX_SEPARATOR;

import org.neo4j.kernel.configuration.ssl.LegacySslPolicyConfig;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import org.junit.Test;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Result;
import org.neo4j.graphdb.Transaction;
import org.neo4j.harness.ServerControls;
import org.neo4j.harness.TestServerBuilder;
import org.neo4j.harness.TestServerBuilders;
import org.neo4j.server.ServerTestUtils;
import org.neo4j.test.server.HTTP;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.CollectionType;
import com.fasterxml.jackson.databind.type.TypeFactory;

/**
 * Created by jbarrasa on 14/09/2016.
 */
public class RDFEndpointTest {
	
	private static final ObjectMapper jsonMapper = new ObjectMapper();
	
	private static final CollectionType collectionType = TypeFactory
			.defaultInstance().constructCollectionType(Set.class, Map.class);

	@Test
    public void testGetNodeById() throws Exception
    {
        // Given
        try ( ServerControls server = getServerBuilder()
                .withExtension( "/rdf", RDFEndpoint.class )
                .withFixture( new Function<GraphDatabaseService, Void>()
                {
                    @Override
                    public Void apply( GraphDatabaseService graphDatabaseService ) throws RuntimeException
                    {
                        try ( Transaction tx = graphDatabaseService.beginTx() )
                        {
                            String ontoCreation = "MERGE (p:Category {catName: \"Person\"})\n" +
                                    "MERGE (a:Category {catName: \"Actor\"})\n" +
                                    "MERGE (d:Category {catName: \"Director\"})\n" +
                                    "MERGE (c:Category {catName: \"Critic\"})\n" +
                                    "CREATE (a)-[:SCO]->(p)\n" +
                                    "CREATE (d)-[:SCO]->(p)\n" +
                                    "CREATE (c)-[:SCO]->(p)\n" +
                                    "RETURN *";
                            graphDatabaseService.execute(ontoCreation);
                            String dataInsertion = "CREATE (Keanu:Actor {name:'Keanu Reeves', born:1964})\n" +
                                    "CREATE (Carrie:Director {name:'Carrie-Anne Moss', born:1967})\n" +
                                    "CREATE (Laurence:Director {name:'Laurence Fishburne', born:1961})\n" +
                                    "CREATE (Hugo:Critic {name:'Hugo Weaving', born:1960})\n" +
                                    "CREATE (AndyW:Actor {name:'Andy Wachowski', born:1967})\n" +
                                    "CREATE (Hugo)-[:WORKS_WITH]->(AndyW)\n" +
                                    "CREATE (Hugo)<-[:FRIEND_OF]-(Carrie)";
                            graphDatabaseService.execute(dataInsertion);
                            tx.success();
                        }
                        return null;
                    }
                } )
                .newServer() )
        {
            // When
            Result result = server.graph().execute( "MATCH (n:Critic) return id(n) as id " );
            Long id = (Long) result.next().get("id");
            assertEquals(new Long(7), id);

            // When
            HTTP.Response response = HTTP.withHeaders(new String[]{"Accept", "application/ld+json"}).GET(
                    HTTP.GET( server.httpURI().resolve( "rdf" ).toString() ).location() + "describe/id?nodeid=" + id.toString());

            Set<Map<String, String>> expected = jsonMapper.readValue("[ {\n" +
                    "  \"@id\" : \"neo4j://indiv#5\",\n" +
                    "  \"neo4j://vocabulary#FRIEND_OF\" : [ {\n" +
                    "    \"@id\" : \"neo4j://indiv#7\"\n" +
                    "  } ]\n" +
                    "}, {\n" +
                    "  \"@id\" : \"neo4j://indiv#7\",\n" +
                    "  \"@type\" : [ \"neo4j://vocabulary#Critic\" ],\n" +
                    "  \"neo4j://vocabulary#WORKS_WITH\" : [ {\n" +
                    "    \"@id\" : \"neo4j://indiv#8\"\n" +
                    "  } ],\n" +
                    "  \"neo4j://vocabulary#born\" : [ {\n" +
                    "    \"@type\" : \"http://www.w3.org/2001/XMLSchema#long\",\n" +
                    "    \"@value\" : \"1960\"\n" +
                    "  } ],\n" +
                    "  \"neo4j://vocabulary#name\" : [ {\n" +
                    "    \"@value\" : \"Hugo Weaving\"\n" +
                    "  } ]\n" +
                    "} ]", collectionType);
            Set<Map<String, String>> resultSet = jsonMapper
            		.readValue(response.rawContent(), collectionType);
            assertEquals( expected, resultSet );
            assertEquals( 200, response.status() );

        }
    }

    @Test
    public void testGetNodeByIdNotFoundOrInvalid() throws Exception
    {
        // Given
        try ( ServerControls server = getServerBuilder()
                .withExtension( "/rdf", RDFEndpoint.class ).newServer() )
        {
            HTTP.Response response = HTTP.withHeaders(new String[]{"Accept", "application/ld+json"}).GET(
                    HTTP.GET( server.httpURI().resolve( "rdf" ).toString() ).location() + "describe/id?nodeid=9999999");

            assertEquals( "[]", response.rawContent() );
            assertEquals( 200, response.status() );

            //TODO: Non Long param for ID (would be a good idea to be consistent with previous case?...)
            response = HTTP.withHeaders(new String[]{"Accept", "application/ld+json"}).GET(
                    HTTP.GET( server.httpURI().resolve( "rdf" ).toString() ).location() + "describe/id?nodeid=adb");

            assertEquals( "", response.rawContent() );
            assertEquals( 404, response.status() );

        }
    }

    @Test
    public void testGetNodeByUriNotFoundOrInvalid() throws Exception
    {
        // Given
        try ( ServerControls server = getServerBuilder()
                .withExtension( "/rdf", RDFEndpoint.class ).newServer() )
        {
            HTTP.Response response = HTTP.withHeaders(new String[]{"Accept", "application/ld+json"}).GET(
                    HTTP.GET( server.httpURI().resolve( "rdf" ).toString() ).location() + "describe/uri?uri=9999999");

            assertEquals( "[]", response.rawContent() );
            assertEquals( 200, response.status() );

        }
    }

    @Test
    public void testPing() throws Exception
    {
        // Given
        try ( ServerControls server = getServerBuilder()
                .withExtension( "/rdf", RDFEndpoint.class ).newServer() )
        {
            HTTP.Response response = HTTP.GET(
                    HTTP.GET( server.httpURI().resolve( "rdf" ).toString() ).location() + "ping");

            assertEquals( "{\"ping\":\"here!\"}", response.rawContent() );
            assertEquals( 200, response.status() );

        }
    }

    @Test
    public void testCypherOnLPG() throws Exception
    {
        // Given
        try ( ServerControls server = getServerBuilder()
                .withExtension( "/rdf", RDFEndpoint.class )
                .withFixture( new Function<GraphDatabaseService, Void>()
                {
                    @Override
                    public Void apply( GraphDatabaseService graphDatabaseService ) throws RuntimeException
                    {
                        try ( Transaction tx = graphDatabaseService.beginTx() )
                        {
                            String ontoCreation = "MERGE (p:Category {catName: \"Person\"})\n" +
                                    "MERGE (a:Category {catName: \"Actor\"})\n" +
                                    "MERGE (d:Category {catName: \"Director\"})\n" +
                                    "MERGE (c:Category {catName: \"Critic\"})\n" +
                                    "CREATE (a)-[:SCO]->(p)\n" +
                                    "CREATE (d)-[:SCO]->(p)\n" +
                                    "CREATE (c)-[:SCO]->(p)\n" +
                                    "RETURN *";
                            graphDatabaseService.execute(ontoCreation);
                            String dataInsertion = "CREATE (Keanu:Actor {name:'Keanu Reeves', born:1964})\n" +
                                    "CREATE (Carrie:Director {name:'Carrie-Anne Moss', born:1967})\n" +
                                    "CREATE (Laurence:Director {name:'Laurence Fishburne', born:1961})\n" +
                                    "CREATE (Hugo:Critic {name:'Hugo Weaving', born:1960})\n" +
                                    "CREATE (AndyW:Actor {name:'Andy Wachowski', born:1967})";
                            graphDatabaseService.execute(dataInsertion);
                            tx.success();
                        }
                        return null;
                    }
                } )
                .newServer() )
        {

            Result result = server.graph().execute( "MATCH (n:Critic) return id(n) as id " );
            assertEquals( 1, count( result ) );

            HTTP.Response response = HTTP.withHeaders(new String[]{"Accept", "text/plain"}).POST(
                    HTTP.GET( server.httpURI().resolve( "rdf" ).toString() ).location() + "cypher", "MATCH (n:Category)--(m:Category) RETURN n,m LIMIT 4");

            assertEquals( "<neo4j://indiv#0> <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <neo4j://vocabulary#Category> .\n" +
                    "<neo4j://indiv#0> <neo4j://vocabulary#catName> \"Person\" .\n" +
                    "<neo4j://indiv#3> <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <neo4j://vocabulary#Category> .\n" +
                    "<neo4j://indiv#3> <neo4j://vocabulary#catName> \"Critic\" .\n" +
                    "<neo4j://indiv#2> <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <neo4j://vocabulary#Category> .\n" +
                    "<neo4j://indiv#2> <neo4j://vocabulary#catName> \"Director\" .\n" +
                    "<neo4j://indiv#1> <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <neo4j://vocabulary#Category> .\n" +
                    "<neo4j://indiv#1> <neo4j://vocabulary#catName> \"Actor\" .\n", response.rawContent() );
            assertEquals( 200, response.status() );

        }
    }

    @Test
    public void testNodeByUri() throws Exception
    {
        // Given
        try ( ServerControls server = getServerBuilder()
                .withExtension( "/rdf", RDFEndpoint.class )
                .withFixture( new Function<GraphDatabaseService, Void>()
                {
                    @Override
                    public Void apply( GraphDatabaseService graphDatabaseService ) throws RuntimeException
                    {
                        try ( Transaction tx = graphDatabaseService.beginTx() )
                        {
                            String nsDefCreation = "CREATE (n:NamespacePrefixDefinition { `http://ont.thomsonreuters.com/mdaas/` : 'ns1' ,\n" +
                                    "`http://permid.org/ontology/organization/` : 'ns0' } ) ";
                            graphDatabaseService.execute(nsDefCreation);
                            String dataInsertion = "CREATE (Keanu:Resource:ns0" + PREFIX_SEPARATOR + "Actor {ns1" + PREFIX_SEPARATOR + "name:'Keanu Reeves', ns1" + PREFIX_SEPARATOR + "born:1964, uri: 'https://permid.org/1-21523433750' })\n" +
                                    "CREATE (Carrie:Resource:ns0_Director {ns1" + PREFIX_SEPARATOR + "name:'Carrie-Anne Moss', ns1" + PREFIX_SEPARATOR + "born:1967, uri: 'https://permid.org/1-21523433751' })\n" +
                                    "CREATE (Laurence:Resource:ns0_Director {ns1" + PREFIX_SEPARATOR + "name:'Laurence Fishburne', ns1" + PREFIX_SEPARATOR + "born:1961, uri: 'https://permid.org/1-21523433752' })\n" +
                                    "CREATE (Hugo:Resource:ns0_Critic {ns1" + PREFIX_SEPARATOR + "name:'Hugo Weaving', ns1" + PREFIX_SEPARATOR + "born:1960, uri: 'https://permid.org/1-21523433753' })\n" +
                                    "CREATE (AndyW:Resource:ns0_Actor {ns1" + PREFIX_SEPARATOR + "name:'Andy Wachowski', ns1" + PREFIX_SEPARATOR + "born:1967, uri: 'https://permid.org/1-21523433754' })\n" +
                                    "CREATE (Keanu)-[:ns0" + PREFIX_SEPARATOR + "Likes]->(Carrie) \n" +
                                    "CREATE (Keanu)<-[:ns0" + PREFIX_SEPARATOR + "FriendOf]-(Hugo) ";
                            graphDatabaseService.execute(dataInsertion);
                            tx.success();
                        }
                        return null;
                    }
                } )
                .newServer() )
        {

            Result result = server.graph().execute( "MATCH (n:ns0_Critic) return id(n) as id " );
            //assertEquals( 1, count( result ) );

            Long id = (Long)result.next().get("id");

            HTTP.Response response = HTTP.withHeaders(new String[]{"Accept", "text/turtle"}).GET(
                    HTTP.GET(server.httpURI().resolve("rdf").toString()).location() + "describe/uri?nodeuri=https://permid.org/1-21523433750");
            //TODO Make it better
            String expected = "@prefix neovoc: <neo4j://vocabulary#> .\n" +
                    "\n" +
                    "\n" +
                    "<https://permid.org/1-21523433750> a <http://permid.org/ontology/organization/Actor> ;\n" +
                    "\t<http://ont.thomsonreuters.com/mdaas/born> \"1964\"^^<http://www.w3.org/2001/XMLSchema#long> ;\n" +
                    "\t<http://ont.thomsonreuters.com/mdaas/name> \"Keanu Reeves\" .\n" +
                    "\n" +
                    "<https://permid.org/1-21523433753> <http://permid.org/ontology/organization/FriendOf> <https://permid.org/1-21523433750> .\n" +
                    "\n" +
                    "<https://permid.org/1-21523433750> <http://permid.org/ontology/organization/Likes> <https://permid.org/1-21523433751> .";
            assertEquals( expected.replaceAll("[\n|\r]", "").trim(),
            		response.rawContent().replaceAll("[\n|\r]", "").trim() );
            assertEquals( 200, response.status() );
        }
    }


    @Test
    public void testCypherWithUrisSerializeAsJsonLd() throws Exception
    {
        // Given
        try ( ServerControls server = getServerBuilder()
                .withExtension( "/rdf", RDFEndpoint.class )
                .withFixture( new Function<GraphDatabaseService, Void>()
                {
                    @Override
                    public Void apply( GraphDatabaseService graphDatabaseService ) throws RuntimeException
                    {
                        try ( Transaction tx = graphDatabaseService.beginTx() )
                        {
                            String nsDefCreation = "CREATE (n:NamespacePrefixDefinition { `http://ont.thomsonreuters.com/mdaas/` : 'ns1' ,\n" +
                                    "`http://permid.org/ontology/organization/` : 'ns0' } ) ";
                            graphDatabaseService.execute(nsDefCreation);
                            String dataInsertion = "CREATE (Keanu:Resource:ns0" + PREFIX_SEPARATOR + "Actor {ns1"+ PREFIX_SEPARATOR +"name:'Keanu Reeves', ns1"+ PREFIX_SEPARATOR +"born:1964, uri: 'https://permid.org/1-21523433750' })\n" +
                                    "CREATE (Carrie:Resource:ns0" + PREFIX_SEPARATOR + "Director {ns1"+ PREFIX_SEPARATOR +"name:'Carrie-Anne Moss', ns1"+ PREFIX_SEPARATOR +"born:1967, uri: 'https://permid.org/1-21523433751' })\n" +
                                    "CREATE (Laurence:Resource:ns0" + PREFIX_SEPARATOR + "Director {ns1"+ PREFIX_SEPARATOR +"name:'Laurence Fishburne', ns1"+ PREFIX_SEPARATOR +"born:1961, uri: 'https://permid.org/1-21523433752' })\n" +
                                    "CREATE (Hugo:Resource:ns0" + PREFIX_SEPARATOR + "Critic {ns1"+ PREFIX_SEPARATOR +"name:'Hugo Weaving', ns1"+ PREFIX_SEPARATOR +"born:1960, uri: 'https://permid.org/1-21523433753' })\n" +
                                    "CREATE (AndyW:Resource:ns0" + PREFIX_SEPARATOR + "Actor {ns1"+ PREFIX_SEPARATOR +"name:'Andy Wachowski', ns1"+ PREFIX_SEPARATOR +"born:1967, uri: 'https://permid.org/1-21523433754' })\n" +
                                    "CREATE (Keanu)-[:ns0" + PREFIX_SEPARATOR + "Likes]->(Carrie) ";
                            graphDatabaseService.execute(dataInsertion);
                            tx.success();
                        }
                        return null;
                    }
                } )
                .newServer() )
        {

            Result result = server.graph().execute( "MATCH (n:ns0" + PREFIX_SEPARATOR + "Critic) return id(n) as id " );
            //assertEquals( 1, count( result ) );

            Long id = (Long)result.next().get("id");

            HTTP.Response response = HTTP.withHeaders(new String[]{"Accept", "application/ld+json"}).POST(
                    HTTP.GET(server.httpURI().resolve("rdf").toString()).location() + "cypheronrdf", "MATCH (n:Resource) RETURN n LIMIT 1");

            Set<Map<String, String>> expected = jsonMapper.readValue("[ {\n" +
                    "  \"@id\" : \"https://permid.org/1-21523433750\",\n" +
                    "  \"@type\" : [ \"http://permid.org/ontology/organization/Actor\" ],\n" +
                    "  \"http://ont.thomsonreuters.com/mdaas/born\" : [ {\n" +
                    "    \"@type\" : \"http://www.w3.org/2001/XMLSchema#long\",\n" +
                    "    \"@value\" : \"1964\"\n" +
                    "  } ],\n" +
                    "  \"http://ont.thomsonreuters.com/mdaas/name\" : [ {\n" +
                    "    \"@value\" : \"Keanu Reeves\"\n" +
                    "  } ]\n" +
                    "} ]", collectionType);
            Set<Map<String, String>> resultSet = jsonMapper.readValue(response.rawContent(),
            		collectionType);
            assertEquals( expected , resultSet );

            assertEquals( 200, response.status() );
        }
    }

    @Test
    public void testCypherWithBNodesSerializeAsRDFXML() throws Exception
    {
        // Given
        try ( ServerControls server = getServerBuilder()
                .withExtension( "/rdf", RDFEndpoint.class )
                .withFixture( new Function<GraphDatabaseService, Void>()
                {
                    @Override
                    public Void apply( GraphDatabaseService graphDatabaseService ) throws RuntimeException
                    {
                        try ( Transaction tx = graphDatabaseService.beginTx() )
                        {
                            String nsDefCreation = "CREATE (n:NamespacePrefixDefinition { `http://ont.thomsonreuters.com/mdaas/` : 'ns1' ,\n" +
                                    "`http://permid.org/ontology/organization/` : 'ns0' } ) ";
                            graphDatabaseService.execute(nsDefCreation);
                            String dataInsertion = "CREATE (Keanu:Resource:ns0" + PREFIX_SEPARATOR + "Actor {ns1"+ PREFIX_SEPARATOR +"name:'Keanu Reeves', ns1"+ PREFIX_SEPARATOR +"born:1964, uri: '_:1-21523433750' })\n" +
                                    "CREATE (Carrie:Resource:ns0"+ PREFIX_SEPARATOR +"Director {ns1" + PREFIX_SEPARATOR + "name:'Carrie-Anne Moss', ns1"+ PREFIX_SEPARATOR +"born:1967, uri: 'https://permid.org/1-21523433751' })\n" +
                                    "CREATE (Laurence:Resource:ns0"+ PREFIX_SEPARATOR +"Director {ns1" + PREFIX_SEPARATOR + "name:'Laurence Fishburne', ns1"+ PREFIX_SEPARATOR +"born:1961, uri: 'https://permid.org/1-21523433752' })\n" +
                                    "CREATE (Hugo:Resource:ns0"+ PREFIX_SEPARATOR +"Critic {ns1" + PREFIX_SEPARATOR + "name:'Hugo Weaving', ns1"+ PREFIX_SEPARATOR +"born:1960, uri: 'https://permid.org/1-21523433753' })\n" +
                                    "CREATE (AndyW:Resource:ns0"+ PREFIX_SEPARATOR +"Actor {ns1" + PREFIX_SEPARATOR + "name:'Andy Wachowski', ns1"+ PREFIX_SEPARATOR + "born:1967, uri: 'https://permid.org/1-21523433754' })\n" +
                                    "CREATE (Keanu)-[:ns0" + PREFIX_SEPARATOR + "Likes]->(Carrie) ";
                            graphDatabaseService.execute(dataInsertion);
                            tx.success();
                        }
                        return null;
                    }
                } )
                .newServer() )
        {

            Result result = server.graph().execute( "MATCH (n:ns0"+ PREFIX_SEPARATOR +"Critic) return id(n) as id " );
            //assertEquals( 1, count( result ) );

            Long id = (Long)result.next().get("id");

            HTTP.Response response = HTTP.withHeaders(new String[]{"Accept", "application/rdf+xml"}).POST(
                    HTTP.GET(server.httpURI().resolve("rdf").toString()).location() + "cypheronrdf","MATCH (a)-[r:ns0" + PREFIX_SEPARATOR + "Likes]-(b) RETURN *");

            assertEquals( "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                    "<rdf:RDF\n" +
                    "\txmlns:neovoc=\"neo4j://vocabulary#\"\n" +
                    "\txmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">\n" +
                    "\n" +
                    "<rdf:Description rdf:about=\"https://permid.org/1-21523433751\">\n" +
                    "\t<rdf:type rdf:resource=\"http://permid.org/ontology/organization/Director\"/>\n" +
                    "\t<born xmlns=\"http://ont.thomsonreuters.com/mdaas/\" rdf:datatype=\"http://www.w3.org/2001/XMLSchema#long\">1967</born>\n" +
                    "\t<name xmlns=\"http://ont.thomsonreuters.com/mdaas/\">Carrie-Anne Moss</name>\n" +
                    "</rdf:Description>\n" +
                    "\n" +
                    "<rdf:Description rdf:about=\"_:1-21523433750\">\n" +
                    "\t<Likes xmlns=\"http://permid.org/ontology/organization/\" rdf:resource=\"https://permid.org/1-21523433751\"/>\n" +
                    "\t<rdf:type rdf:resource=\"http://permid.org/ontology/organization/Actor\"/>\n" +
                    "\t<born xmlns=\"http://ont.thomsonreuters.com/mdaas/\" rdf:datatype=\"http://www.w3.org/2001/XMLSchema#long\">1964</born>\n" +
                    "\t<name xmlns=\"http://ont.thomsonreuters.com/mdaas/\">Keanu Reeves</name>\n" +
                    "\t<Likes xmlns=\"http://permid.org/ontology/organization/\" rdf:resource=\"https://permid.org/1-21523433751\"/>\n" +
                    "</rdf:Description>\n" +
                    "\n" +
                    "</rdf:RDF>" , response.rawContent() );

            assertEquals( 200, response.status() );
        }
    }

    private TestServerBuilder getServerBuilder( ) throws IOException
    {
        TestServerBuilder serverBuilder = TestServerBuilders.newInProcessBuilder();
        serverBuilder.withConfig( LegacySslPolicyConfig.certificates_directory.name(),
                ServerTestUtils.getRelativePath( getSharedTestTemporaryFolder(), LegacySslPolicyConfig.certificates_directory ) );
        return serverBuilder;
    }
}
