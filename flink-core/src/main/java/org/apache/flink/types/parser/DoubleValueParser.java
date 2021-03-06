/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


package org.apache.flink.types.parser;

import org.apache.flink.types.DoubleValue;

/**
 * Parses a text field into a DoubleValue.
 */
public class DoubleValueParser extends FieldParser<DoubleValue> {
	
	private DoubleValue result;
	
	@Override
	public int parseField(byte[] bytes, int startPos, int limit, byte[] delimiter, DoubleValue reusable) {
		
		int i = startPos;

		final int delimLimit = limit - delimiter.length + 1;

		while (i < limit) {
			if (i < delimLimit && delimiterNext(bytes, i, delimiter)) {
				break;
			}
			i++;
		}
		
		if (i > startPos &&
				(Character.isWhitespace(bytes[startPos]) || Character.isWhitespace(bytes[i - 1]))) {
			setErrorState(ParseErrorState.NUMERIC_VALUE_ILLEGAL_CHARACTER);
			return -1;
		}

		String str = new String(bytes, startPos, i - startPos);
		try {
			double value = Double.parseDouble(str);
			reusable.setValue(value);
			this.result = reusable;
			return (i == limit) ? limit : i + delimiter.length;
		}
		catch (NumberFormatException e) {
			setErrorState(ParseErrorState.NUMERIC_VALUE_FORMAT_ERROR);
			return -1;
		}
	}
	
	@Override
	public DoubleValue createValue() {
		return new DoubleValue();
	}

	@Override
	public DoubleValue getLastResult() {
		return this.result;
	}
}
