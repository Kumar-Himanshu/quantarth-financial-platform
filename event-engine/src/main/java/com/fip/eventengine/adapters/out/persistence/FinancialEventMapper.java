package com.fip.eventengine.adapters.out.persistence;

import com.fip.eventengine.domain.model.FinancialEvent;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface FinancialEventMapper {

    FinancialEvent toDomain(FinancialEventJpaEntity entity);

    FinancialEventJpaEntity toEntity(FinancialEvent domain);
}
