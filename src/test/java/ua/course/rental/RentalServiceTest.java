package ua.course.rental;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RentalServiceTest {

    private EquipmentRepository equipmentRepo;
    private RentalRepository rentalRepo;
    private RentalPolicy policy;
    private RentalService rentalService;

    @BeforeEach
    void setUp() {
        equipmentRepo = mock(EquipmentRepository.class);
        rentalRepo = mock(RentalRepository.class);
        policy = mock(RentalPolicy.class);
        rentalService = new RentalService(equipmentRepo, rentalRepo, policy);
    }

    // 1. Параметризований тест для базової формули розрахунку (Варіант 15: rate * qty * days)
    @ParameterizedTest
    @CsvSource({
            "100, 1, 1, 100",
            "500, 2, 5, 5000",
            "100000, 10, 10, 10000000",
            "1500, 4, 10, 60000"
    })
    void calculateTotal_ValidInputs_ReturnsCorrectTotal(long rate, int quantity, int days, long expectedTotal) {
        long actualTotal = rentalService.calculateTotal(rate, quantity, days);
        assertEquals(expectedTotal, actualTotal);
    }

    // 2. Параметризований тест для преміального і звичайного клієната
    // @ParameterizedTest
    //@CsvSource({
    //       "1000, 3, 30, false, true",
    //       "1000, 4, 7, false, true",
    //       "1000, 4, 8, false, false",
    //      "1000, 10, 30, true, true",
    //       "1000, 11, 7, true, true",
    //       "1000, 11, 8, true, false"
    // })
    //void isRentalAllowed_Variant15Rules_ReturnsExpectedResult(long rate, int qty, int days, boolean premium, boolean expected) {
    //   boolean result = rentalService.isRentalAllowed(rate, qty, days, premium);
    //  assertEquals(expected, result);
    // }

    // 3. Нижня межа ціни
    @Test
    void calculateTotal_RateBelowMinimum_ThrowsIllegalArgumentException() {
        long invalidRate = 99;
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> rentalService.calculateTotal(invalidRate, 1, 1)
        );
        assertNotNull(exception);
    }

    // 4. Верхня межа кількості
    @Test
    void calculateTotal_QuantityAboveMaximum_ThrowsIllegalArgumentException() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> rentalService.calculateTotal(500, 21, 5) // Максимум 20 одиниць
        );
        assertNotNull(exception);
    }

    // 5. Нестача на склад
    @Test
    void rent_QuantityExceedsStock_ThrowsRentalRejectedException() throws SQLException {
        RentalRequest request = new RentalRequest("REF123", 1L, 5, 2, false);
        Equipment mockEquipment = new Equipment(1L, "EQ-1", 500, 3); // На складі лише 3
        when(equipmentRepo.findById(1L)).thenReturn(mockEquipment);

        assertThrows(RentalRejectedException.class, () -> rentalService.rent(request));
    }

    // 6. Перевірка взаємодії та відсутності зміни залишків
    @Test
    void rent_DoesNotUpdateEquipmentStock() throws SQLException {
        RentalRequest request = new RentalRequest("REF-TEST", 1L, 2, 3, false);
        Equipment mockEquipment = new Equipment(1L, "CODE", 1000, 10);
        RentalOrder mockOrder = new RentalOrder(100L, "REF-TEST", 2, false, 6000, 0, RentalStatus.Active, null);

        when(equipmentRepo.findById(1L)).thenReturn(mockEquipment);
        when(policy.isAllowed(mockEquipment, 2, 3)).thenReturn(true);
        when(rentalRepo.create(any(RentalOrder.class))).thenReturn(mockOrder);

        rentalService.rent(request);

        verify(equipmentRepo, never()).update(any(Equipment.class));
    }

    // 7. Успішне оформлення оренди
    @Test
    void rent_ValidRequest_CreatesAndReturnsOrder() throws SQLException {
        RentalRequest request = new RentalRequest("REF123", 1L, 2, 3, false);
        Equipment mockEquipment = new Equipment(1L, "CODE", 1000, 10);
        RentalOrder expectedOrder = new RentalOrder(100L, "REF123", 2, false, 6000, 0, RentalStatus.Active, null);

        when(equipmentRepo.findById(1L)).thenReturn(mockEquipment);
        when(policy.isAllowed(mockEquipment, 2, 3)).thenReturn(true);
        when(rentalRepo.create(any(RentalOrder.class))).thenReturn(expectedOrder);

        RentalOrder actualOrder = rentalService.rent(request);

        // Перевірка стану
        assertNotNull(actualOrder);
        assertEquals(100L, actualOrder.id());
        assertEquals(RentalStatus.Active, actualOrder.status());

        // Перевірка взаємодії
        verify(policy, times(1)).isAllowed(mockEquipment, 2, 3);
        verify(rentalRepo, times(1)).create(any(RentalOrder.class));
    }

    // 8. Нульова доплата
    @Test
    void returnRental_ValidId_CallsMarkReturnedWithZeroLateFee() throws SQLException {
        long rentalId = 1L;
        RentalOrder mockOrder = new RentalOrder(rentalId, "REF123", 5, false, 5000, 0, RentalStatus.Active, null);
        when(rentalRepo.findById(rentalId)).thenReturn(mockOrder);

        rentalService.returnRental(rentalId);

        verify(rentalRepo, times(1)).markReturned(rentalId, 0L);
    }

    // 9. Спроба повернути вже повернене
    @Test
    void returnRental_AlreadyReturned_ThrowsRentalRejectedException() throws SQLException {
        long rentalId = 2L;
        RentalOrder returnedOrder = new RentalOrder(rentalId, "REF123", 5, false, 500, 0, RentalStatus.Returned, null);
        when(rentalRepo.findById(rentalId)).thenReturn(returnedOrder);

        assertThrows(RentalRejectedException.class, () -> rentalService.returnRental(rentalId));
    }

    // Вбиває мутантів 12, 23, 42 Граничні перевірки
    @ParameterizedTest
    @CsvSource({
            "99, 1, 1",
            "1000001, 1, 1",
            "100, 0, 1",
            "100, 21, 1",
            "100, 1, 0",
            "100, 1, 31"
    })
    void validate_InvalidBoundaries_ThrowsIllegalArgumentException(long rate, int qty, int days) {
        assertThrows(IllegalArgumentException.class, () -> rentalService.calculateTotal(rate, qty, days));
        assertThrows(IllegalArgumentException.class, () -> rentalService.rent(new RentalRequest("REF", 1L, qty, days, false)));
    }

    // Вбиває мутанта 25 Обладнання не знайдено
    @Test
    void rent_EquipmentNotFound_ThrowsIllegalArgumentException() throws SQLException {
        when(equipmentRepo.findById(99L)).thenReturn(null);
        RentalRequest request = new RentalRequest("REF", 99L, 1, 1, false);
        assertThrows(IllegalArgumentException.class, () -> rentalService.rent(request));
    }

    // Вбиває мутанта 26
    @Test
    void rent_QuantityEqualsStock_Success() throws SQLException {
        Equipment eq = new Equipment(1L, "CODE", 1000, 3); // Залишок рівно 3
        RentalOrder order = new RentalOrder(1L, "REF", 3, false, 3000, 0, RentalStatus.Active, null);
        when(equipmentRepo.findById(1L)).thenReturn(eq);
        when(policy.isAllowed(eq, 3, 1)).thenReturn(true);
        when(rentalRepo.create(any())).thenReturn(order);

        RentalOrder result = rentalService.rent(new RentalRequest("REF", 1L, 3, 1, false));
        assertNotNull(result);
    }

    // Вбиває мутантів 27, 28
    @Test
    void rent_ExternalPolicyRejects_ThrowsRentalRejectedException() throws SQLException {
        Equipment eq = new Equipment(1L, "CODE", 1000, 10);
        when(equipmentRepo.findById(1L)).thenReturn(eq);
        when(policy.isAllowed(eq, 2, 3)).thenReturn(false); // Політика забороняє

        RentalRequest request = new RentalRequest("REF", 1L, 2, 3, false);
        assertThrows(RentalRejectedException.class, () -> rentalService.rent(request));
    }

    // Вбиває мутанта 36 Оренда для повернення не знайдена
    @Test
    void returnRental_OrderNotFound_ThrowsIllegalArgumentException() throws SQLException {
        when(rentalRepo.findById(99L)).thenReturn(null);
        assertThrows(IllegalArgumentException.class, () -> rentalService.returnRental(99L));
    }

    // Вбиває мутантів 34, 39
    @Test
    void returnRental_ValidId_ReturnsNotNull() throws SQLException {
        RentalOrder mockOrder = new RentalOrder(1L, "REF", 2, false, 2000, 0, RentalStatus.Active, null);
        when(rentalRepo.findById(1L)).thenReturn(mockOrder);

        RentalOrder result = rentalService.returnRental(1L);
        assertNotNull(result, "Метод returnRental не повинен повертати null");
    }
}