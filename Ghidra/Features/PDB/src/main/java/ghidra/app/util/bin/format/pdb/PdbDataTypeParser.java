/* ###
 * IP: GHIDRA
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ghidra.app.util.bin.format.pdb;

import java.util.*;

import ghidra.app.services.DataTypeManagerService;
import ghidra.program.database.data.DataTypeUtilities;
import ghidra.program.model.data.*;
import ghidra.util.Msg;
import ghidra.util.exception.CancelledException;
import ghidra.util.task.TaskMonitor;

class PdbDataTypeParser {

	private final static String NO_TYPE = "NoType";

	private DataTypeManager programDataTypeMgr;
	private DataTypeManagerService service;
	private TaskMonitor monitor;

	private Map<String, DataType> dataTypeCache = new HashMap<>();

	PdbDataTypeParser(DataTypeManager programDataTypeMgr, DataTypeManagerService service,
			TaskMonitor monitor) {
		this.programDataTypeMgr = programDataTypeMgr;
		this.service = service;
		this.monitor = monitor;
		createMandatoryDataTypes();
	}

	private void createMandatoryDataTypes() {

		addDataType(new TypedefDataType("wchar", WideCharDataType.dataType));

		addDataType(new TypedefDataType("__int8",
			AbstractIntegerDataType.getSignedDataType(1, programDataTypeMgr)));
		addDataType(new TypedefDataType("__uint8",
			AbstractIntegerDataType.getUnsignedDataType(1, programDataTypeMgr)));

		addDataType(new TypedefDataType("__int16",
			AbstractIntegerDataType.getSignedDataType(2, programDataTypeMgr)));
		addDataType(new TypedefDataType("__uint16",
			AbstractIntegerDataType.getUnsignedDataType(2, programDataTypeMgr)));

		addDataType(new TypedefDataType("__int32",
			AbstractIntegerDataType.getSignedDataType(4, programDataTypeMgr)));
		addDataType(new TypedefDataType("__uint32",
			AbstractIntegerDataType.getUnsignedDataType(2, programDataTypeMgr)));

		addDataType(new TypedefDataType("__int64",
			AbstractIntegerDataType.getSignedDataType(8, programDataTypeMgr)));
		addDataType(new TypedefDataType("__uint64",
			AbstractIntegerDataType.getUnsignedDataType(8, programDataTypeMgr)));
	}

	/**
	 * Get the target program's datatype manager
	 * @return program's datatype manager
	 */
	public DataTypeManager getProgramDataTypeManager() {
		return programDataTypeMgr;
	}

	void flushDataTypeCache() {
		for (DataType dt : dataTypeCache.values()) {
			programDataTypeMgr.resolve(dt,
				DataTypeConflictHandler.REPLACE_EMPTY_STRUCTS_OR_RENAME_AND_ADD_HANDLER);
		}
		dataTypeCache.clear();
	}

	/**
	 * Ensures that the data type managers are used in a particular order.
	 * The order is as follows:
	 *    1) the program's data type manager
	 *    2) the built-in data type manager
	 *    3) the open data type archives
	 */
	private class PdbDataTypeManagerComparator implements Comparator<DataTypeManager> {
		@Override
		public int compare(DataTypeManager dtm1, DataTypeManager dtm2) {
			if (dtm1 == programDataTypeMgr) {
				return -1;
			}
			if (dtm2 == programDataTypeMgr) {
				return 1;
			}
			if (dtm1 instanceof BuiltInDataTypeManager) {
				return -1;
			}
			if (dtm2 instanceof BuiltInDataTypeManager) {
				return 1;
			}
			return 0;
		}
	}

	void clear() {
		dataTypeCache.clear();
	}

	DataType getCachedDataType(String key) {
		return dataTypeCache.get(key);
	}

	void cacheDataType(String key, DataType dataType) {
		dataTypeCache.put(key, dataType);
	}

	void addDataType(DataType dataType) {

		if (dataType instanceof Composite) {
			DataTypeComponent[] components = ((Composite) dataType).getComponents();
			for (DataTypeComponent component : components) {
				addDataType(component.getDataType());
			}
		}

		ArrayList<DataType> oldDataTypeList = new ArrayList<>();
		programDataTypeMgr.findDataTypes(dataType.getName(), oldDataTypeList);
		dataType = programDataTypeMgr.addDataType(dataType,
			DataTypeConflictHandler.REPLACE_EMPTY_STRUCTS_OR_RENAME_AND_ADD_HANDLER);

		cacheDataType(dataType.getName(), dataType);

		for (DataType oldDataType : oldDataTypeList) {
			if (oldDataType.getLength() == 0 &&
				oldDataType.getClass().equals(dataType.getClass())) {
				try {
					programDataTypeMgr.replaceDataType(oldDataType, dataType, false);
				}
				catch (DataTypeDependencyException e) {
					// ignore
				}
			}
		}
	}

	private DataType findDataTypeInArchives(String datatype, TaskMonitor monitor)
			throws CancelledException {

		DataTypeManager[] managers = service.getDataTypeManagers();
		Arrays.sort(managers, new PdbDataTypeManagerComparator());
		for (DataTypeManager manager : managers) {
			if (monitor.isCancelled()) {
				throw new CancelledException();
			}
			DataType dt = DataTypeUtilities.findNamespaceQualifiedDataType(manager, datatype, null);
			if (dt != null) {
				cacheDataType(datatype, dt);
				return dt;
			}
		}
		return null;
	}

	private DataType findBaseDataType(String dataTypeName, TaskMonitor monitor)
			throws CancelledException {
		DataType dt = getCachedDataType(dataTypeName);
		if (dt != null) {
			return dt;
		}

		// PDP category does not apply to built-ins which always live at the root

		BuiltInDataTypeManager builtInDTM = BuiltInDataTypeManager.getDataTypeManager();
		dt = builtInDTM.getDataType(new DataTypePath(CategoryPath.ROOT, dataTypeName));
		if (dt == null) {
			dt = findDataTypeInArchives(dataTypeName, monitor);
		}
		return dt;
	}

	/**
	 * Find a data-type by name in a case-sensitive manner.
	 * @param monitor task monitor
	 * @param dataTypeName data-type name (may be qualified by its namespace)
	 * @return wrapped data-type or null if not found.
	 * @throws CancelledException if operation is cancelled
	 */
	public WrappedDataType findDataType(String datatype) throws CancelledException {

		// NOTE: previous case-insensitive search was removed since type names
		// should be case-sensitive
		datatype = datatype.trim();

		if (datatype == null || datatype.length() == 0) {
			return null;
		}

		if (NO_TYPE.equals(datatype)) {
			return new WrappedDataType(VoidDataType.dataType, false); //TODO make it void?
		}

		String dataTypeName = datatype;

		// Example type representations:
		// char *[2][3]     pointer(array(array(char,3),2))
		// char *[2][3] *   pointer(array(array(pointer(char),3),2))
		// char  [0][2][3] *  array(array(array(pointer(char),3),2),0)
		// char  [2][3] *    array(array(pointer(char),3),2)

		int basePointerDepth = 0;
		while (dataTypeName.endsWith("*")) {
			++basePointerDepth;
			dataTypeName = dataTypeName.substring(0, dataTypeName.length() - 1).trim();
		}

		boolean isZeroLengthArray = false;
		List<Integer> arrayDimensions = null;
		if (dataTypeName.endsWith("]")) {
			arrayDimensions = new ArrayList<>();
			dataTypeName = parseArrayDimensions(dataTypeName, arrayDimensions);
			if (dataTypeName == null) {
				Msg.error(this, "Failed to parse array dimensions: " + datatype);
				return null;
			}
			isZeroLengthArray = (arrayDimensions.get(arrayDimensions.size() - 1) == 0);
		}

		int pointerDepth = 0;
		if (arrayDimensions != null) {
			while (dataTypeName.endsWith("*")) {
				++pointerDepth;
				dataTypeName = dataTypeName.substring(0, dataTypeName.length() - 1).trim();
			}
			if (pointerDepth != 0 && isZeroLengthArray) {
				Msg.error(this, "Unsupported pointer to zero-length array: " + datatype);
				return null;
			}
		}

		// Find base data-type (name may include namespace, e.g., Foo::MyType)
		// Primary cache (dataTypeCache) may contain namespace qualified data type

		DataType dt = findBaseDataType(dataTypeName, monitor);
		if (dt == null) {
			return null; // base type not found
		}

		while (basePointerDepth-- != 0) {
			dt = createPointer(dt);
		}

		if (arrayDimensions != null) {
			dt = createArray(dt, arrayDimensions);
		}

		while (pointerDepth-- != 0) {
			dt = createPointer(dt);
		}

		return new WrappedDataType(dt, isZeroLengthArray);
	}

	private String parseArrayDimensions(String datatype, List<Integer> arrayDimensions) {
		String dataTypeName = datatype;
		boolean zeroLengthArray = false;
		while (dataTypeName.endsWith("]")) {
			if (zeroLengthArray) {
				return null; // only last dimension may be 0
			}
			int rBracketPos = dataTypeName.lastIndexOf(']');
			int lBracketPos = dataTypeName.lastIndexOf('[');
			if (lBracketPos < 0) {
				return null;
			}
			int dimension;
			try {
				dimension = Integer.parseInt(dataTypeName.substring(lBracketPos + 1, rBracketPos));
				if (dimension < 0) {
					return null; // invalid dimension
				}
			}
			catch (NumberFormatException e) {
				return null;
			}
			dataTypeName = dataTypeName.substring(0, lBracketPos).trim();
			arrayDimensions.add(dimension);
		}
		return dataTypeName;
	}

	DataType createPointer(DataType dt) {
		return PointerDataType.getPointer(dt, programDataTypeMgr);
	}

	private DataType createArray(DataType dt, List<Integer> arrayDimensions) {
		int dimensionCount = arrayDimensions.size();
		boolean zeroLengthArray = arrayDimensions.get(arrayDimensions.size() - 1) == 0;
		if (zeroLengthArray) {
			--dimensionCount;
		}
		for (int i = 0; i < dimensionCount; i++) {
			int dimension = arrayDimensions.get(i);
			dt = new ArrayDataType(dt, dimension, dt.getLength(), programDataTypeMgr);
		}
		if (zeroLengthArray) {
			// This should be temporary for supported flex-array cases,
			// although we do not really support flex-arrays within unions
			// or in the middle of structures.
			dt = new ArrayDataType(dt, 1, dt.getLength(), programDataTypeMgr);
		}
		return dt;
	}

}
