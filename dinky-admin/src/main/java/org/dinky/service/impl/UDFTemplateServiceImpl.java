/*
 *
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

package org.dinky.service.impl;

import org.dinky.assertion.Asserts;
import org.dinky.data.exception.BusException;
import org.dinky.data.model.UDFTemplate;
import org.dinky.mapper.UDFTemplateMapper;
import org.dinky.mybatis.service.impl.SuperServiceImpl;
import org.dinky.service.UDFTemplateService;

import org.springframework.stereotype.Service;

import cn.hutool.core.util.StrUtil;

/** @since 0.6.8 */
@Service
public class UDFTemplateServiceImpl extends SuperServiceImpl<UDFTemplateMapper, UDFTemplate>
        implements UDFTemplateService {

    @Override
    public boolean saveOrUpdate(UDFTemplate udfTemplate) {
        UDFTemplate selectOne = query().eq("name", udfTemplate.getName()).one();
        udfTemplate.setCodeType(StrUtil.upperFirst(udfTemplate.getCodeType().toLowerCase()));
        if (Asserts.isNull(udfTemplate.getId())) {
            if ((selectOne != null)) {
                throw new BusException("the template name already exists");
            }
            return save(udfTemplate);
        } else {
            if (Asserts.isNotNull(selectOne) && !udfTemplate.getId().equals(selectOne.getId())) {
                throw new BusException("the template name already exists");
            }
            return updateById(udfTemplate);
        }
    }

    /**
     * @param id {@link Integer}
     * @return {@link Boolean}
     */
    @Override
    public Boolean modifyUDFTemplateStatus(Integer id) {
        UDFTemplate udfTemplate = getById(id);
        udfTemplate.setEnabled(!udfTemplate.getEnabled());
        if (updateById(udfTemplate)) {
            return true;
        }
        return false;
    }
}
