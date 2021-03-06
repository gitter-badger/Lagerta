/*
 * Copyright (c) 2017. EPAM Systems
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.epam.lagerta.base.util;

import com.epam.lagerta.base.BlobValueTransformer;
import com.epam.lagerta.base.EnumValueTransformer;
import com.epam.lagerta.base.FieldDescriptor;
import com.epam.lagerta.base.SimpleValueTransformer;
import com.epam.lagerta.base.ValueTransformer;
import com.epam.lagerta.util.Serializer;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import static com.epam.lagerta.base.EntityDescriptor.KEY_FIELD_NAME;
import static com.epam.lagerta.base.EntityDescriptor.VAL_FIELD_NAME;

public class FieldDescriptorHelper {

    private final BlobValueTransformer blobValueTransformer;

    public FieldDescriptorHelper(Serializer serializer) {
        this.blobValueTransformer = new BlobValueTransformer(serializer);
    }

    public <T> List<FieldDescriptor> parseFields(Class<T> clazz) {
        int[] index = new int[]{1};
        List<FieldDescriptor> descriptors = new ArrayList<>();
        ReflectionUtils.doWithFields(clazz, field -> {
            ReflectionUtils.makeAccessible(field);
            Class<?> type = field.getType();
            ValueTransformer transformer = identifyTransformer(type);
            descriptors.add(new FieldDescriptor(index[0]++, field.getName(), transformer));
        }, field -> {
            int modifiers = field.getModifiers();
            return !Modifier.isTransient(modifiers) && !Modifier.isStatic(modifiers);
        });
        return addDefaultDescriptors(descriptors);
    }

    private ValueTransformer identifyTransformer(Class<?> type) {
        ValueTransformer transformer;
        if (type.isEnum()) {
            transformer = EnumValueTransformer.of(type);
        } else {
            transformer = SimpleValueTransformer.of(type);
            if (transformer == SimpleValueTransformer.DUMMY) {
                transformer = blobValueTransformer;
            }
        }
        return transformer;
    }

    /**
     * adds default fieldDescriptors at the end of table columns by indexes
     */
    public List<FieldDescriptor> addDefaultDescriptors(List<FieldDescriptor> fieldDescriptors) {
        int lastIndex = fieldDescriptors
                .stream()
                .mapToInt(FieldDescriptor::getIndex)
                .max().orElse(0);

        if (fieldDescriptors.stream().noneMatch(field -> field.getName().equals(KEY_FIELD_NAME))) {
            fieldDescriptors.add(new FieldDescriptor(++lastIndex, KEY_FIELD_NAME, SimpleValueTransformer.OBJECT));
        }
        if (fieldDescriptors.stream().noneMatch(field -> field.getName().equals(VAL_FIELD_NAME))) {
            fieldDescriptors.add(new FieldDescriptor(++lastIndex, VAL_FIELD_NAME, blobValueTransformer));
        }
        return fieldDescriptors;
    }
}
