package org.folio.rest.impl;

import io.vertx.core.Context;
import org.folio.HttpStatus;
import org.folio.orders.rest.exceptions.HttpException;
import org.folio.orders.utils.ProtectedOperationType;
import org.folio.rest.jaxrs.model.*;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static java.util.stream.Collectors.toList;
import static org.folio.orders.utils.ErrorCodes.*;
import static org.folio.orders.utils.HelperUtils.convertIdsToCqlQuery;
import static org.folio.rest.RestVerticle.OKAPI_USERID_HEADER;

public class ProtectionHelper extends AbstractHelper {

  private AcquisitionsUnitsHelper acquisitionsUnitsHelper;
  private AcquisitionsUnitAssignmentsHelper acquisitionsUnitAssignmentsHelper;
  private ProtectedOperationType operation;


  public ProtectionHelper(Map<String, String> okapiHeaders, Context ctx, String lang, ProtectedOperationType operation) {
    super(okapiHeaders, ctx, lang);
    acquisitionsUnitsHelper = new AcquisitionsUnitsHelper(okapiHeaders, ctx, lang);
    acquisitionsUnitAssignmentsHelper = new AcquisitionsUnitAssignmentsHelper(okapiHeaders, ctx, lang);
    this.operation = operation;
  }

  /**
   * This method determines status of operation restriction based on ID of {@link CompositePurchaseOrder}.
   * @param recordId corresponding record ID.
   *
   * @return true if operation is restricted, otherwise - false.
   */
  public CompletableFuture<Boolean> isOperationRestricted(String recordId) {
    String userId;
    if(okapiHeaders != null && (userId = okapiHeaders.get(OKAPI_USERID_HEADER)) != null) {
      return getUnitIdsAssignedToOrder(recordId)
        .thenCompose(unitIds -> isOperationRestricted(userId, unitIds));
    } else {
      throw new HttpException(HttpStatus.HTTP_FORBIDDEN.toInt(), UNKNOWN_USER);
    }
  }

  /**
   * This method determines status of operation restriction based on unit IDs from {@link CompositePurchaseOrder}.
   * @param unitIds list of unit IDs.
   *
   * @return true if operation is restricted, otherwise - false.
   */
  public CompletableFuture<Boolean> isOperationRestricted(List<String> unitIds) {
    String userId;
    if(okapiHeaders != null && (userId = okapiHeaders.get(OKAPI_USERID_HEADER)) != null) {
        return isOperationRestricted(userId, unitIds);
    } else {
      throw new HttpException(HttpStatus.HTTP_FORBIDDEN.toInt(), UNKNOWN_USER);
    }
  }

  /**
   * This method determines status of operation restriction based on units analysis and user ID.
   * @param unitIds list of unit's IDs.
   * @param userId user's ID.
   *
   * @return true if operation is restricted, otherwise - false.
   */
  private CompletableFuture<Boolean> isOperationRestricted(String userId, List<String> unitIds) {
    if(!unitIds.isEmpty()) {
      CompletableFuture<Boolean> future = new CompletableFuture<>();
      getUnitsByIds(unitIds)
        .thenApply(units -> {
          if(!units.isEmpty() && unitIds.size() == units.size()) {
            return applyMergingStrategy(units)
              .thenAccept(isOperationProtected -> {
                if(isOperationProtected) {
                  getUnitIdsAssignedToUserAndOrder(userId, unitIds)
                    .thenAccept(ids -> future.complete(ids.isEmpty()));
                } else {
                  future.complete(false);
                }
              });
          } else {
            future.completeExceptionally(new HttpException(HttpStatus.HTTP_VALIDATION_ERROR.toInt(), ORDER_UNITS_NOT_FOUND));
          }
          return future;
        });
      return future;
    } else {
      return CompletableFuture.completedFuture(false);
    }
  }

  /**
   * This method checks existence of units associated with order.
   * @param recordId id of order.
   *
   * @return true if units exist, otherwise - false.
   */
  private CompletableFuture<List<String>> getUnitIdsAssignedToOrder(String recordId) {
    return acquisitionsUnitAssignmentsHelper.getAcquisitionsUnitAssignments(String.format("recordId==%s", recordId), 0, Integer.MAX_VALUE)
      .thenApply(assignment -> assignment.getAcquisitionsUnitAssignments().stream().map(AcquisitionsUnitAssignment::getAcquisitionsUnitId).collect(toList()));
  }

  /**
   * This method returns list of units ids associated with User.
   * @param userId id of User.
   *
   * @return list of unit ids associated with user.
   */
  private CompletableFuture<List<String>> getUnitIdsAssignedToUserAndOrder(String userId, List<String> unitIdsAssignedToOrder) {
    String query = String.format("userId==%s AND %s", userId, convertIdsToCqlQuery(unitIdsAssignedToOrder, "acquisitionsUnitId"));
    return acquisitionsUnitsHelper.getAcquisitionsUnitsMemberships(query, 0, Integer.MAX_VALUE)
      .thenApply(memberships -> memberships.getAcquisitionsUnitMemberships().stream()
        .map(AcquisitionsUnitMembership::getAcquisitionsUnitId).collect(toList()));
  }

  /**
   * This method returns list of {@link AcquisitionsUnit} based on list of unit ids
   * @param unitIds list of unit ids
   *
   * @return list of {@link AcquisitionsUnit}
   */
  private CompletableFuture<List<AcquisitionsUnit>> getUnitsByIds(List<String> unitIds) {
    String query = convertIdsToCqlQuery(unitIds);
    return acquisitionsUnitsHelper.getAcquisitionsUnits(query, 0, Integer.MAX_VALUE)
      .thenApply(AcquisitionsUnitCollection::getAcquisitionsUnits);
  }

  /**
   * This method returns operation protection resulted status based on list of units with using least restrictive wins strategy.
   *
   * @param units list of {@link AcquisitionsUnit}.
   * @return true if operation is protected, otherwise - false.
   */
  private CompletableFuture<Boolean> applyMergingStrategy(List<AcquisitionsUnit> units) {
    return CompletableFuture.completedFuture(units.stream().allMatch(unit -> operation.isProtected(unit)));
  }

}
