package net.osmand.data.preparation;

import gnu.trove.list.array.TIntArrayList;

import java.io.File;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import net.osmand.IProgress;
import net.osmand.IndexConstants;
import net.osmand.binary.BinaryMapPoiReaderAdapter;
import net.osmand.data.Amenity;
import net.osmand.data.AmenityType;
import net.osmand.impl.ConsoleProgressImplementation;
import net.osmand.osm.MapRenderingTypes;
import net.osmand.osm.MapRenderingTypes.MapRulType;
import net.osmand.osm.edit.Entity;
import net.osmand.osm.edit.Entity.EntityId;
import net.osmand.osm.edit.EntityParser;
import net.osmand.osm.edit.OSMSettings.OSMTagKey;
import net.osmand.osm.edit.Relation;
import net.osmand.util.Algorithms;
import net.osmand.util.MapUtils;
import net.sf.junidecode.Junidecode;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

public class IndexPoiCreator extends AbstractIndexPartCreator {

	private static final Log log = LogFactory.getLog(IndexPoiCreator.class);

	private Connection poiConnection;
	private File poiIndexFile;
	private PreparedStatement poiPreparedStatement;
	private static final int ZOOM_TO_SAVE_END = 16;
	private static final int ZOOM_TO_SAVE_START = 6;
	private static final int ZOOM_TO_WRITE_CATEGORIES_START = 12;
	private static final int ZOOM_TO_WRITE_CATEGORIES_END = 16;
	private static final int CHARACTERS_TO_BUILD = 4;
	private boolean useInMemoryCreator = true; 
	

	private List<Amenity> tempAmenityList = new ArrayList<Amenity>();
	private Map<EntityId, Map<String, String>> propogatedTags = new LinkedHashMap<Entity.EntityId, Map<String, String>>();

	private final MapRenderingTypes renderingTypes;

	public IndexPoiCreator(MapRenderingTypes renderingTypes) {
		this.renderingTypes = renderingTypes;
	}

	public void iterateEntity(Entity e, OsmDbAccessorContext ctx) throws SQLException {
		tempAmenityList.clear();
		EntityId eid = EntityId.valueOf(e);
		Map<String, String> tags = propogatedTags.get(eid);
		if (tags != null) {
			Iterator<Entry<String, String>> iterator = tags.entrySet().iterator();
			while (iterator.hasNext()) {
				Entry<String, String> ts = iterator.next();
				if (e.getTag(ts.getKey()) == null) {
					e.putTag(ts.getKey(), ts.getValue());
				}
			}
		}
		boolean privateReg = "private".equals(e.getTag("access")); 
		tempAmenityList = EntityParser.parseAmenities(renderingTypes, e, tempAmenityList);
		if (!tempAmenityList.isEmpty() && poiPreparedStatement != null) {
			if(e instanceof Relation) {
				ctx.loadEntityRelation((Relation) e);
			}
			for (Amenity a : tempAmenityList) {
				if(a.getType() == AmenityType.LEISURE && privateReg) {
					// don't index private swimming pools 
					continue;
				}
				// do not add that check because it is too much printing for batch creation
				// by statistic < 1% creates maps manually
				// checkEntity(e);
				EntityParser.parseMapObject(a, e);
				if (a.getLocation() != null) {
					// do not convert english name
					// convertEnglishName(a);
					insertAmenityIntoPoi(a);
				}
			}
		}
	}
	
	public void iterateRelation(Relation e, OsmDbAccessorContext ctx) throws SQLException {
		for (String t : e.getTagKeySet()) {
			AmenityType type = renderingTypes.getAmenityTypeForRelation(t, e.getTag(t));
			if (type != null) {
				ctx.loadEntityRelation(e);
				for (EntityId id : ((Relation) e).getMembersMap().keySet()) {
					if (!propogatedTags.containsKey(id)) {
						propogatedTags.put(id, new LinkedHashMap<String, String>());
					}
					propogatedTags.get(id).put(t, e.getTag(t));
				}
			}
		}
	}

	public void commitAndClosePoiFile(Long lastModifiedDate) throws SQLException {
		closeAllPreparedStatements();
		if (poiConnection != null) {
			poiConnection.commit();
			poiConnection.close();
			poiConnection = null;
			if (lastModifiedDate != null) {
				poiIndexFile.setLastModified(lastModifiedDate);
			}
		}
	}
	
	public void removePoiFile(){
		Algorithms.removeAllFiles(poiIndexFile);
	}

	public void checkEntity(Entity e) {
		String name = e.getTag(OSMTagKey.NAME);
		if (name == null) {
			String msg = "";
			Collection<String> keys = e.getTagKeySet();
			int cnt = 0;
			for (Iterator<String> iter = keys.iterator(); iter.hasNext();) {
				String key = iter.next();
				if (key.startsWith("name:") && key.length() <= 8) {
					// ignore specialties like name:botanical
					if (cnt == 0)
						msg += "Entity misses default name tag, but it has localized name tag(s):\n";
					msg += key + "=" + e.getTag(key) + "\n";
					cnt++;
				}
			}
			if (cnt > 0) {
				msg += "Consider adding the name tag at " + e.getOsmUrl();
				log.warn(msg);
			}
		}
	}

	private void insertAmenityIntoPoi(Amenity amenity) throws SQLException {
		assert IndexConstants.POI_TABLE != null : "use constants here to show table usage "; //$NON-NLS-1$
		poiPreparedStatement.setLong(1, amenity.getId());
		poiPreparedStatement.setInt(2, MapUtils.get31TileNumberX(amenity.getLocation().getLongitude()));
		poiPreparedStatement.setInt(3, MapUtils.get31TileNumberY(amenity.getLocation().getLatitude()));
		poiPreparedStatement.setString(4, AmenityType.valueToString(amenity.getType()));
		poiPreparedStatement.setString(5, amenity.getSubType());
		poiPreparedStatement.setString(6, encodeAdditionalInfo(amenity.getAdditionalInfo(), amenity.getName(), amenity.getEnName()));
		addBatch(poiPreparedStatement);
	}
	
	private static final char SPECIAL_CHAR = ((char) -1);
	private String encodeAdditionalInfo(Map<String, String> tempNames, String name, String nameEn) {
		if(!Algorithms.isEmpty(name)) {
			tempNames.put("name", name);
		}
		if(!Algorithms.isEmpty(nameEn) && !Algorithms.objectEquals(name, nameEn)) {
			tempNames.put("name:en", nameEn);
		}
		StringBuilder b = new StringBuilder();
		for (Map.Entry<String, String> e : tempNames.entrySet()) {
			MapRulType rulType = renderingTypes.getAmenityRuleType(e.getKey(), e.getValue());
			if(rulType == null) {
				throw new IllegalStateException("Can't retrieve amenity rule type " + e.getKey() + " " + e.getValue());
			}
			if(!rulType.isText() ||  !Algorithms.isEmpty(e.getValue())) {
				if(b.length() > 0){
					b.append(SPECIAL_CHAR);
				}
				if(rulType.isAdditional() && rulType.getValue() == null) {
					throw new IllegalStateException("Additional rule type '" + rulType.getTag() + "' should be encoded with value '"+e.getValue() +"'");
				}
				b.append((char)(rulType.getInternalId()) ).append(e.getValue());
			}
		}
		return b.toString();
	}

	private Map<MapRulType, String> decodeAdditionalInfo(String name, 
			Map<MapRulType, String> tempNames) {
		tempNames.clear();
		if(name.length() == 0) {
			return tempNames;
		}
		int i, p = 0;
		while(true) {
			i = name.indexOf(SPECIAL_CHAR, p);
			String t = i == -1 ? name.substring(p) : name.substring(p, i);
			MapRulType rulType = renderingTypes.getTypeByInternalId(t.charAt(0));
			tempNames.put(rulType, t.substring(1));
			if(rulType.isAdditional() && rulType.getValue() == null) {
				throw new IllegalStateException("Additional rule type '" + rulType.getTag() + "' should be encoded with value '"+t.substring(1) +"'");
			}
			if(i == -1) {
				break;
			}
			p = i + 1;
		}
		return tempNames;
	}
	
	public void createDatabaseStructure(File poiIndexFile) throws SQLException {
		this.poiIndexFile = poiIndexFile;
		// delete previous file to save space
		if (poiIndexFile.exists()) {
			Algorithms.removeAllFiles(poiIndexFile);
		}
		poiIndexFile.getParentFile().mkdirs();
		// creating connection
		poiConnection = (Connection) DBDialect.SQLITE.getDatabaseConnection(poiIndexFile.getAbsolutePath(), log);

		// create database structure
		Statement stat = poiConnection.createStatement();
		stat.executeUpdate("create table " + IndexConstants.POI_TABLE + //$NON-NLS-1$
				" (id bigint, x int, y int,"
				+ "type varchar(1024), subtype varchar(1024), additionalTags varchar(8096), "
				+ "primary key(id, type, subtype))");
		stat.executeUpdate("create index poi_loc on poi (x, y, type, subtype)");
		stat.executeUpdate("create index poi_id on poi (id, type, subtype)");
		stat.execute("PRAGMA user_version = " + IndexConstants.POI_TABLE_VERSION); //$NON-NLS-1$
		stat.close();

		// create prepared statment
		poiPreparedStatement = poiConnection
				.prepareStatement("INSERT INTO " + IndexConstants.POI_TABLE + "(id, x, y, type, subtype, additionalTags) " + //$NON-NLS-1$//$NON-NLS-2$
						"VALUES (?, ?, ?, ?, ?, ?)");
		pStatements.put(poiPreparedStatement, 0);

		poiConnection.setAutoCommit(false);
	}

	
	
	private class IntBbox {
		int minX = Integer.MAX_VALUE;
		int maxX = 0;
		int minY = Integer.MAX_VALUE;
		int maxY = 0;
	}
	
	public static class PoiCreatorCategories {
		Map<String, Set<String>> categories = new HashMap<String, Set<String>>();
		Set<MapRulType> additionalAttributes = new HashSet<MapRenderingTypes.MapRulType>();
		
		
		// build indexes to write  
		Map<String, Integer> catIndexes;
		Map<String, Integer> subcatIndexes;
		
		TIntArrayList cachedCategoriesIds;
		TIntArrayList cachedAdditionalIds;
		
		// cache for single thread
		TIntArrayList singleThreadVarTypes = new TIntArrayList();
		
		
		private String[] split(String subCat) {
			return subCat.split(",|;");
		}

		private boolean toSplit(String subCat) {
			return subCat.contains(";") || subCat.contains(",");
		}
		
		public void addCategory(String cat, String subCat, Map<MapRulType, String> additionalTags){
			for (MapRulType rt : additionalTags.keySet()) {
				if(rt.isAdditional() && rt.getValue() == null) {
					throw new NullPointerException("Null value for additional tag =" + rt.getTag());
				}
				additionalAttributes.add(rt);
			}
			if(!categories.containsKey(cat)){
				categories.put(cat, new TreeSet<String>());
			}
			if (toSplit(subCat)) {
				String[] split = split(subCat);
				for (String sub : split) {
					categories.get(cat).add(sub.trim());
				}
			} else {
				categories.get(cat).add(subCat.trim());
			}
		}

		public TIntArrayList buildTypeIds(String category, String subcategory) {
			singleThreadVarTypes.clear();
			TIntArrayList types = singleThreadVarTypes;
			internalBuildType(category, subcategory, types);
			return types;
		}
		
		private void internalBuildType(String category, String subcategory, TIntArrayList types) {
			int catInd = catIndexes.get(category);
			if (toSplit(subcategory)) {
				for (String sub : split(subcategory)) {
					Integer subcatInd = subcatIndexes.get(category + SPECIAL_CHAR + sub.trim());
					if (subcatInd == null) {
						throw new IllegalArgumentException("Unknown subcategory " + sub + " category " + category);
					}
					types.add((subcatInd << BinaryMapPoiReaderAdapter.SHIFT_BITS_CATEGORY) | catInd);
				}
			} else {
				Integer subcatInd = subcatIndexes.get(category + SPECIAL_CHAR + subcategory.trim());
				if (subcatInd == null) {
					throw new IllegalArgumentException("Unknown subcategory " + subcategory + " category " + category);
				}
				types.add((subcatInd << BinaryMapPoiReaderAdapter.SHIFT_BITS_CATEGORY) | catInd);
			}
		}

		public void buildCategoriesToWrite(PoiCreatorCategories globalCategories) {
			cachedCategoriesIds = new TIntArrayList();
			cachedAdditionalIds = new TIntArrayList();
			for(Map.Entry<String, Set<String>> cats : categories.entrySet()) {
				for(String subcat : cats.getValue()){
					String cat = cats.getKey();
					globalCategories.internalBuildType(cat, subcat, cachedCategoriesIds);
				}
			}
			for(MapRulType rt : additionalAttributes){
				if(rt.getTargetPoiId() == -1) {
					throw new IllegalStateException("Map rule type is not registered for poi : " + rt);
				}
				cachedAdditionalIds.add(rt.getTargetPoiId());
			}
		}

		public void setSubcategoryIndex(String cat, String sub, int j) {
			if(subcatIndexes == null) {
				subcatIndexes = new HashMap<String, Integer>();
			}
			subcatIndexes.put(cat + SPECIAL_CHAR + sub, j);
		}

		public void setCategoryIndex(String cat, int i) {
			if(catIndexes == null) {
				catIndexes = new HashMap<String, Integer>();
			}
			catIndexes.put(cat, i);			
		}
	}
	
	public void writeBinaryPoiIndex(BinaryMapIndexWriter writer, String regionName, IProgress progress) throws SQLException, IOException {
		if (poiPreparedStatement != null) {
			closePreparedStatements(poiPreparedStatement);
		}
		poiConnection.commit();
		
		Map<String, Set<PoiTileBox>> namesIndex = new TreeMap<String, Set<PoiTileBox>>();
		
		int zoomToStart = ZOOM_TO_SAVE_START;
		IntBbox bbox = new IntBbox();
		Tree<PoiTileBox> rootZoomsTree = new Tree<PoiTileBox>();
		// 0. process all entities
		processPOIIntoTree(namesIndex, zoomToStart, bbox, rootZoomsTree);
		
		// 1. write header
		long startFpPoiIndex = writer.startWritePoiIndex(regionName, bbox.minX, bbox.maxX, bbox.maxY, bbox.minY);

		// 2. write categories table
		PoiCreatorCategories globalCategories = rootZoomsTree.node.categories;
		writer.writePoiCategoriesTable(globalCategories);
		writer.writePoiSubtypesTable(globalCategories);
		
		// 2.5 write names table
		Map<PoiTileBox, List<BinaryFileReference>> fpToWriteSeeks = writer.writePoiNameIndex(namesIndex, startFpPoiIndex);

		// 3. write boxes
		log.info("Poi box processing finished");
		int level = 0;
		for (; level < (ZOOM_TO_SAVE_END - zoomToStart); level++) {
			int subtrees = rootZoomsTree.getSubTreesOnLevel(level);
			if (subtrees > 8) {
				level--;
				break;
			}
		}
		if (level > 0) {
			rootZoomsTree.extractChildrenFromLevel(level);
			zoomToStart = zoomToStart + level;
		}

		// 3.2 write tree using stack
		for (Tree<PoiTileBox> subs : rootZoomsTree.getSubtrees()) {
			writePoiBoxes(writer, subs, startFpPoiIndex, fpToWriteSeeks, globalCategories);
		}

		// 4. write poi data
		// not so effictive probably better to load in memory one time
		PreparedStatement prepareStatement = poiConnection
				.prepareStatement("SELECT id, x, y, type, subtype, additionalTags from poi "
						+ "where x >= ? AND x < ? AND y >= ? AND y < ?");
		for (Map.Entry<PoiTileBox, List<BinaryFileReference>> entry : fpToWriteSeeks.entrySet()) {
			int z = entry.getKey().zoom;
			int x = entry.getKey().x;
			int y = entry.getKey().y;
			writer.startWritePoiData(z, x, y, entry.getValue());

			if(useInMemoryCreator){
				List<PoiData> poiData = entry.getKey().poiData;
				
				for(PoiData poi : poiData){
					int x31 = poi.x;
					int y31 = poi.y;
					String type = poi.type;
					String subtype = poi.subtype;
					int x24shift = (x31 >> 7) - (x << (24 - z));
					int y24shift = (y31 >> 7) - (y << (24 - z));
					writer.writePoiDataAtom(poi.id, x24shift, y24shift, type, subtype, poi.additionalTags, renderingTypes, 
							globalCategories);	
				}
				
			} else {
				prepareStatement.setInt(1, x << (31 - z));
				prepareStatement.setInt(2, (x + 1) << (31 - z));
				prepareStatement.setInt(3, y << (31 - z));
				prepareStatement.setInt(4, (y + 1) << (31 - z));
				ResultSet rset = prepareStatement.executeQuery();
				Map<MapRulType, String> mp = new HashMap<MapRulType, String>();
				while (rset.next()) {
					long id = rset.getLong(1);
					int x31 = rset.getInt(2);
					int y31 = rset.getInt(3);
					int x24shift = (x31 >> 7) - (x << (24 - z));
					int y24shift = (y31 >> 7) - (y << (24 - z));
					String type = rset.getString(4);
					String subtype = rset.getString(5);
					writer.writePoiDataAtom(id, x24shift, y24shift,  type, subtype, 
							decodeAdditionalInfo(rset.getString(6), mp), renderingTypes, globalCategories);
				}
				rset.close();
			}
			writer.endWritePoiData();
		}

		prepareStatement.close();

		writer.endWritePoiIndex();

	}

	private void processPOIIntoTree(Map<String, Set<PoiTileBox>> namesIndex, int zoomToStart, IntBbox bbox,
			Tree<PoiTileBox> rootZoomsTree) throws SQLException {
		ResultSet rs;
		if(useInMemoryCreator) {
			rs = poiConnection.createStatement().executeQuery("SELECT x,y,type,subtype,id,additionalTags from poi");
		} else {
			rs = poiConnection.createStatement().executeQuery("SELECT x,y,type,subtype from poi");
		}
		rootZoomsTree.setNode(new PoiTileBox());
		
		int count = 0;
		ConsoleProgressImplementation console = new ConsoleProgressImplementation();
		console.startWork(1000000);
		Map<MapRulType, String> additionalTags = new LinkedHashMap<MapRulType, String>();
		MapRulType nameRuleType = renderingTypes.getNameRuleType();
		MapRulType nameEnRuleType = renderingTypes.getNameEnRuleType();
		while (rs.next()) {
			int x = rs.getInt(1);
			int y = rs.getInt(2);
			bbox.minX = Math.min(x, bbox.minX);
			bbox.maxX = Math.max(x, bbox.maxX);
			bbox.minY = Math.min(y, bbox.minY);
			bbox.maxY = Math.max(y, bbox.maxY);
			if(count++ > 10000){
				count = 0;
				console.progress(10000);
			}

			String type = rs.getString(3);
			String subtype = rs.getString(4);
			decodeAdditionalInfo(rs.getString(6), additionalTags);

			Tree<PoiTileBox> prevTree = rootZoomsTree;
			rootZoomsTree.getNode().categories.addCategory(type, subtype, additionalTags);
			for (int i = zoomToStart; i <= ZOOM_TO_SAVE_END; i++) {
				int xs = x >> (31 - i);
				int ys = y >> (31 - i);
				Tree<PoiTileBox> subtree = null;
				for (Tree<PoiTileBox> sub : prevTree.getSubtrees()) {
					if (sub.getNode().x == xs && sub.getNode().y == ys && sub.getNode().zoom == i) {
						subtree = sub;
						break;
					}
				}
				if (subtree == null) {
					subtree = new Tree<PoiTileBox>();
					PoiTileBox poiBox = new PoiTileBox();
					subtree.setNode(poiBox);
					poiBox.x = xs;
					poiBox.y = ys;
					poiBox.zoom = i;

					prevTree.addSubTree(subtree);
				}
				subtree.getNode().categories.addCategory(type, subtype, additionalTags);

				prevTree = subtree;
			}
			addNamePrefix(additionalTags.get(nameRuleType), additionalTags.get(nameEnRuleType), prevTree.getNode(), namesIndex);
			
			if (useInMemoryCreator) {
				if (prevTree.getNode().poiData == null) {
					prevTree.getNode().poiData = new ArrayList<PoiData>();
				}
				PoiData poiData = new PoiData();
				poiData.x = x;
				poiData.y = y;
				poiData.type = type;
				poiData.subtype = subtype;
				poiData.id = rs.getLong(5); 
				poiData.additionalTags.putAll(additionalTags);
				prevTree.getNode().poiData.add(poiData);
				
			}
		}
		log.info("Poi processing finished");
	}
	
	public void addNamePrefix(String name, String nameEn, PoiTileBox data, Map<String, Set<PoiTileBox>> poiData) {
		if (name != null) {
			parsePrefix(name, data, poiData);
			if (Algorithms.isEmpty(nameEn)) {
				nameEn = Junidecode.unidecode(name);
			}
			if (!Algorithms.objectEquals(nameEn, name)) {
				parsePrefix(nameEn, data, poiData);
			}
		}
	}

	private void parsePrefix(String name, PoiTileBox data, Map<String, Set<PoiTileBox>> poiData) {
		int prev = -1;
		for (int i = 0; i <= name.length(); i++) {
			if (i == name.length() || (!Character.isLetter(name.charAt(i)) && !Character.isDigit(name.charAt(i)) && name.charAt(i) != '\'')) {
				if (prev != -1) {
					String substr = name.substring(prev, i);
					if (substr.length() > CHARACTERS_TO_BUILD) {
						substr = substr.substring(0, CHARACTERS_TO_BUILD);
					}
					String val = substr.toLowerCase();
					if(!poiData.containsKey(val)){
						poiData.put(val, new LinkedHashSet<PoiTileBox>());
					}
					poiData.get(val).add(data);
					prev = -1;
				}
			} else {
				if(prev == -1){
					prev = i;
				}
			}
		}
		
	}

	private void writePoiBoxes(BinaryMapIndexWriter writer, Tree<PoiTileBox> tree, 
			long startFpPoiIndex, Map<PoiTileBox, List<BinaryFileReference>> fpToWriteSeeks,
			PoiCreatorCategories globalCategories) throws IOException, SQLException {
		int x = tree.getNode().x;
		int y = tree.getNode().y;
		int zoom = tree.getNode().zoom;
		boolean end = zoom == ZOOM_TO_SAVE_END;
		BinaryFileReference fileRef = writer.startWritePoiBox(zoom, x, y, startFpPoiIndex, end);
		if(fileRef != null){
			if(!fpToWriteSeeks.containsKey(tree.getNode())) {
				fpToWriteSeeks.put(tree.getNode(), new ArrayList<BinaryFileReference>());
			}
			fpToWriteSeeks.get(tree.getNode()).add(fileRef);
		}
		if(zoom >= ZOOM_TO_WRITE_CATEGORIES_START && zoom <= ZOOM_TO_WRITE_CATEGORIES_END){
			PoiCreatorCategories boxCats = tree.getNode().categories;
			boxCats.buildCategoriesToWrite(globalCategories);
			writer.writePoiCategories(boxCats);
		}
		
		if (!end) {
			for (Tree<PoiTileBox> subTree : tree.getSubtrees()) {
				writePoiBoxes(writer, subTree, startFpPoiIndex, fpToWriteSeeks, globalCategories);
			}
		}
		writer.endWritePoiBox();
	}
	
	private static class PoiData {
		int x;
		int y;
		String type;
		String subtype;
		long id;
		Map<MapRulType, String> additionalTags = new HashMap<MapRulType, String>() ;
	}
	
	public static class PoiTileBox {
		int x;
		int y;
		int zoom;
		PoiCreatorCategories categories = new PoiCreatorCategories();
		List<PoiData> poiData = null;
		
		public int getX() {
			return x;
		}
		

		public int getY() {
			return y;
		}
		public int getZoom() {
			return zoom;
		}
		
		

	}

	private static class Tree<T> {

		private T node;
		private List<Tree<T>> subtrees = null;

		public List<Tree<T>> getSubtrees() {
			if (subtrees == null) {
				subtrees = new ArrayList<Tree<T>>();
			}
			return subtrees;
		}

		public void addSubTree(Tree<T> t) {
			getSubtrees().add(t);
		}

		public T getNode() {
			return node;
		}

		public void setNode(T node) {
			this.node = node;
		}

		public void extractChildrenFromLevel(int level) {
			List<Tree<T>> list = new ArrayList<Tree<T>>();
			collectChildrenFromLevel(list, level);
			subtrees = list;
		}

		public void collectChildrenFromLevel(List<Tree<T>> list, int level) {
			if (level == 0) {
				if (subtrees != null) {
					list.addAll(subtrees);
				}
			} else if (subtrees != null) {
				for (Tree<T> sub : subtrees) {
					sub.collectChildrenFromLevel(list, level - 1);
				}

			}

		}

		public int getSubTreesOnLevel(int level) {
			if (level == 0) {
				if (subtrees == null) {
					return 0;
				} else {
					return subtrees.size();
				}
			} else {
				int sum = 0;
				if (subtrees != null) {
					for (Tree<T> t : subtrees) {
						sum += t.getSubTreesOnLevel(level - 1);
					}
				}
				return sum;
			}
		}

	}

}
